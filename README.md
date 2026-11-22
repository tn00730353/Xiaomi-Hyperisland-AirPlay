# LiquidCast

把 Android 系統音訊以立體聲串流到 AirPlay（RAOP）裝置。Apple 液態玻璃風格 UI、小米 HyperIsland / 焦點通知、狀態列音量控制。

用 Android Studio（Ladybug 以上）開啟本資料夾，Run 即可；或 `gradle :app:assembleDebug`。

## 結構
| 檔案 | 作用 |
|---|---|
| `RaopDiscovery.kt` | mDNS 搜尋 `_raop._tcp` |
| `RaopSession.kt` | RTSP 握手、UDP RTP、時間同步、重送、音量 |
| `Alac.kt` | 16-bit 立體聲 PCM → 未壓縮 ALAC 幀 |
| `CastService.kt` | MediaProjection 擷取系統音訊 + 前景服務 + 通知 |
| `HyperIsland.kt` | 小米焦點通知 JSON（超級島顯示狀態與音量） |
| `ui/Glass.kt`, `ui/MainScreen.kt` | 液態玻璃 UI |

## HomePod（AirPlay 2）
HomePod / Apple TV 會自動走 AirPlay 2：transient 配對（PIN 3939）→ 加密 RTSP → 加密 RTP 音訊。
HomePod 系統 27 之後，舊的 AirPlay 1 即使回應成功也不會出聲，所以必須用這條路。
使用前請在「家庭」App → HomePod 設定 → 允許喇叭與電視存取，改成「所有人」或「同一網路上的任何人」，並且不要設密碼。
這段加密協定是依公開文件撰寫，未在實機測試；若連線失敗，畫面上的錯誤訊息會指出卡在哪一步。

## 已知限制
- AirPlay 1 用於 AirPort Express、shairport-sync 等；HomePod 走 AirPlay 2（見上）。需要 HomeKit 正式配對的裝置尚未支援。
- 有密碼的接收端尚未支援。
- 只能擷取允許被錄製的 App 音訊（部分 App 與 DRM 內容會是靜音）。
- HyperIsland 的 JSON 欄位若與你的系統版本不符，只改 `HyperIsland.kt` 的 `buildParamJson`。
