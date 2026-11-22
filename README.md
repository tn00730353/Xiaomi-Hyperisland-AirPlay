# HyperCast

© DylanLiao — 開發者浮水印：所有原始碼檔案開頭、BuildConfig.DEVELOPER、Manifest meta-data 與畫面底部皆標示。

把 Android 系統音訊以立體聲串流到 AirPlay（RAOP / AirPlay 2）裝置。Apple 液態玻璃風格 UI、小米 HyperIsland / 焦點通知、狀態列音量控制、可調整音訊延遲。

## 取得 APK
1. 到 repo 的 **Actions → Build APK → Run workflow**（push 到 main 也會自動執行）。
2. 完成後在該次執行頁面底部 **Artifacts** 下載 `HyperCast-debug-apk`，解壓後安裝 `app-debug.apk`。

## 本機編譯
用 Android Studio（Ladybug 以上）開啟本資料夾後 Run，或執行 `gradle :app:assembleDebug`。

## 使用方式
1. 手機與喇叭 / HomePod / Apple TV 連上同一個 Wi-Fi。
2. 開啟 App，從裝置清單點選要播放的裝置，並允許螢幕錄製（只用來擷取系統音訊）。
3. 在畫面上調整音量，或使用通知 / 狀態列控制。
4. 聲音與畫面不同步時，到 **設定 → 音訊延遲** 以 50 ms 為單位微調（範圍 −1000 ~ +2000 ms）。正值讓聲音更晚播出，負值更早；串流中即可調整，設定會被記住。

## HomePod / Apple TV（AirPlay 2）
HomePod 與 Apple TV 會自動走 AirPlay 2。使用前請在「家庭」App → 該裝置設定 → 允許喇叭與電視存取，改成「所有人」或「同一網路上的任何人」，並且不要設密碼。

## 配對碼 / 密碼
- 裝置要求配對碼時，App 會請裝置顯示配對碼，並跳出輸入框讓你輸入，最多重試 3 次。
- 設有密碼的 AirPlay 1 裝置會跳出密碼輸入框。

## 診斷日誌（設定 → 診斷）
連線失敗時可開啟「日誌記錄」：先顯示同意書（收集項目、用途、權益），同意後才開始記錄之後的連線過程。
- 只存在本機，不自動上傳；「匯出日誌檔」會用系統檔案選擇器存成 `HyperCast_日期_時間_地點.log`。
- IP 末段、MAC、UUID 會遮蔽；不記錄配對碼、金鑰、音訊內容、Wi-Fi 名稱。
- 地點為選用的「概略位置」（約 1 公里精度，只在連線時讀一次）；未授權則檔名為「未知地點」。

## 已知限制
- 只能擷取允許被錄製的 App 音訊（部分 App 與 DRM 內容會是靜音）。
- 需要 HomeKit 正式配對的裝置、以及有密碼的 AirPlay 2 接收端尚未支援。
