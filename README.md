# HyperCast

© DylanLiao — 開發者浮水印：所有原始碼檔案開頭、BuildConfig.DEVELOPER、Manifest meta-data 與畫面底部皆標示。

把 Android 系統音訊以立體聲串流到 AirPlay（RAOP）裝置。Apple 液態玻璃風格 UI、小米 HyperIsland / 焦點通知、狀態列音量控制。

## 取得 APK（不用裝 Android Studio）
1. 在 GitHub 建一個新 repo，把這個資料夾的內容全部上傳（含隱藏的 `.github`）。
2. 到 repo 的 **Actions → Build APK → Run workflow**（push 到 main 也會自動跑）。
3. 完成後在該次執行頁面底部 **Artifacts** 下載 `HyperCast-debug-apk`，解壓後安裝 `app-debug.apk`。

## 本機編譯
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

## 診斷日誌（設定 → 診斷）
連線失敗時可開啟「日誌記錄」：先顯示同意書（收集項目、用途、權益），同意後才開始記錄之後的連線過程（裝置資料、配對各步驟的回應碼與耗時、錯誤與例外堆疊、串流統計）。
- 只存在本機，不自動上傳；「匯出日誌檔」會用系統檔案選擇器存成 `HyperCast_日期_時間_地點.log`。
- IP 末段、MAC、UUID 會遮蔽；不記錄配對碼、金鑰、音訊內容、Wi-Fi 名稱。
- 地點為選用的「概略位置」（約 1 公里精度，只在連線時讀一次）；未授權則檔名為「未知地點」。

## 已知限制
- AirPlay 1 用於 AirPort Express、shairport-sync 等；HomePod 走 AirPlay 2（見上）。需要 HomeKit 正式配對的裝置尚未支援。
- 有密碼的接收端尚未支援。
- 只能擷取允許被錄製的 App 音訊（部分 App 與 DRM 內容會是靜音）。
- HyperIsland 的 JSON 欄位若與你的系統版本不符，只改 `HyperIsland.kt` 的 `buildParamJson`。

## 配對碼 / 密碼（v1.1.3）
- AirPlay 2 裝置要求配對碼時，App 會先請裝置顯示配對碼（/pair-pin-start），並跳出輸入框讓你輸入，最多重試 3 次。
- AirPlay 1 裝置設有密碼（401 Digest）時，同樣會跳出密碼輸入框。
- ANNOUNCE 回 403 的喇叭會自動先送 /auth-setup 再重試。

## v1.1.5
- Apple TV / 組合裝置（回 HTTP 470）：改用 HomeKit 一般配對（X-Apple-HKP: 3，pair-setup M1–M6 + pair-verify），配對憑證存在本機，之後不必再輸入配對碼。
- Sound Pro：在 session SETUP 之後先送 RECORD，再送串流 SETUP（參考 Base1 的順序）。
