# 飞牛服务端接口与踩坑记录

> 本文记录的是**逆向实测**结果，用于说明本项目为什么这样实现。
> 接口属于飞牛（fnOS）私有协议，可能随官方版本更新而变化，仅供学习参考。
> 请只在**自己的**设备与账号上使用。

## 总览

飞牛的服务端协议分两条链路，**缺一不可**：

| 链路 | 作用 | 鉴权方式 |
| --- | --- | --- |
| WebSocket 加密登录 | 换取 `AccessToken` | RSA 公钥 + AES-256-CBC |
| HTTP 业务接口（`/p` 前缀） | 相册、照片列表、媒体流 | `AccessToken` + `authx` 签名 |

## 一、WebSocket 加密登录

### 1. 建立连接

```
ws://<host>:5666/websocket?type=main
```

### 2. 取 RSA 公钥

发：

```json
{"req":"util.crypto.getRSAPub","reqid":"r1"}
```

收：

```json
{"pub":"-----BEGIN PUBLIC KEY-----\n...","si":"72057984125699406","result":"succ"}
```

- `pub` 是 **X.509** 格式（`BEGIN PUBLIC KEY`），**不是** PKCS#1（`BEGIN RSA PUBLIC KEY`）。
  用 `KeyFactory.getInstance("RSA")` + `X509EncodedKeySpec` 直接解就行；
  如果你拿到的是 PKCS#1 的 base64，需要手动补上 `RSA PUBLIC KEY` 的 DER 头再解。
- `si` 是服务端下发的会话标识，**必须原样带回**（见下方「致命坑」）。

### 3. 构造登录包

登录 payload 是一个**扁平 JSON**，形如：

```json
{
  "req": "login",
  "reqid": "r2",
  "si": "72057984125699406",
  "user": "<账号>",
  "password": "<RSA 加密后的密码或明文>",
  "...": "..."
}
```

加密流程：

1. 生成 **32 字符随机串**作为 AES 密钥，取其 **UTF-8 字节**（32 字节）；
2. 用 **AES-256-CBC** 加密 payload，随机生成 16 字节 IV；
3. 用 **RSA/ECB/PKCS1Padding** 加密 AES 密钥；
4. 组包发出：

```json
{
  "req": "encrypted",
  "iv": "<base64>",
  "rsa": "<base64>",
  "aes": "<base64>"
}
```

服务端返回的密文按同样方式解开，即可拿到 `AccessToken` 与用户信息。

## 二、HTTP 业务接口

前缀统一是 **`/p`**，例如：

```
GET /p/api/v1/album/list
GET /p/api/v1/photo/list
GET /p/api/v1/stream/p/t/{id}/{size}/{uuid}     # 缩略图
GET /p/api/v1/stream/v/{id}                     # 视频 / 动图原片
```

### 请求头

```
AccessToken: <登录拿到的 token>
authx: nonce=<随机数>&timestamp=<秒级时间戳>&sign=<签名>
```

### 签名算法

```
paramHash = md5( GET 参数按 key 升序拼成 "k=v"，再用 "&" 连接 )
            md5( POST 时直接用 body 原文 )

sign      = md5( salt + "_" + path + "_" + nonce + "_" + timestamp + "_" + paramHash + "_" + secret )
```

要点：

- `path` **必须带 `/p` 前缀**，不带签不过；
- `secret` 是代码里的固定常量，**与登录返回的 secret 无关**，别混用；
- `paramHash` 参与拼接的是排序后的键值对，顺序错了签名必错。

### 鉴权差异（很重要）

| 接口 | 需要 AccessToken | 需要 authx 签名 |
| --- | --- | --- |
| 相册 / 照片列表 | ✅ | ✅ |
| 缩略图 `stream/p/...` | ✅ | ❌ |
| 视频 / 动图 `stream/v/...` | ✅ | ❌ |

所以图片与视频可以直接交给 Coil / MediaPlayer，只需在拦截器里塞一个 `AccessToken` 头。

## 三、两个致命坑

### 坑 1：`si` 必须原样按字符串回传

服务端返回的 `si` 是**18 位整数的字符串**，例如 `"72057984125699406"`。

如果顺手用 `optLong` / `JSONObject.getLong` 把它转成数字再发回去：

- JSON 数字会走双精度浮点，**末位精度直接丢失**（`...406` → `...410`）；
- 同时字段类型从字符串变成数字，服务端类型校验不通过；
- 结果：**`errno 8192` 登录失败**。

这个坑的现象极具迷惑性 —— 服务端能正确解出密文，甚至能回显 payload 里的 `reqid`，
看起来像「加密算法写错了」，实际只是字段类型问题。

**正确做法**：`obj.optString("si")`，然后用 `JSONObject` 原样塞回去（别经过任何数值类型）。

### 坑 2：`getList` 的 `end_time` 必须补全到 `23:59:59`

列表接口按时间区间查询，如果 `end_time` 只给到日期（`2026-10-01 00:00:00`），
**当天的照片会全部漏掉**。要补成当天最后一秒。

## 四、缩略图档位实测

| size | 分辨率 | 体积 |
| --- | --- | --- |
| `xxs` | 107×60 | 2.2 KB |
| `xs` | 320×180 | 9.7 KB |
| `s` | 427×240 | 16.4 KB |
| `m` | 1920×1080 | 204 KB |
| `l` | 不可用 | — |

本项目的取图策略：

- 六宫格 / 九宫格 → `m`
- 十二宫格 → `s`（格子小，省流量也更快）
- 全屏查看 → `m`

## 五、媒体流（视频 / 实况照片）

- 视频与动图的短片**共用** `/p/api/v1/stream/v/{id}`；
- 地址优先取服务端返回的 `additional.thumbnail.videoUrl`，缺失时按 `id` 兜底拼装；
- **只校验 `AccessToken` 头，不需要 authx 签名**；
- 实测支持 `Range` 请求，返回 `206 Partial Content`，所以可以流式播放与拖动，不用整段下载。

实测结果：

| 类型 | 容器 | 大小示例 | 结果 |
| --- | --- | --- | --- |
| 视频 | `video/mp4`（H.264） | 765 KB / 8s | 自动播放一次，OK 重播 |
| 动图 | `video/quicktime`（HEVC） | 4.5 MB | OK 播放一次，播完回静态图 |

> 动图短片的编码通常是 HEVC（解码器 `OMX.qcom.video.decoder.hevc`），
> 主流电视盒子和模拟器都已支持。

### 怎么识别实况照片

服务端字段 `isLive`（数字 `0` / `1`）标记 iPhone 实况照片，其短片地址在
`additional.thumbnail.videoUrl`。

**两个条件同时满足**才显示「动图」角标、才允许播放 —— 只有 `isLive=1`
但短片还没被 NAS 索引出来的条目，播放必然失败，因此不给角标。
