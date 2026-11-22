# HyperCast v1.2.5

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

## 同時串流到兩台裝置（v1.2.0）
- 閒置時點一台裝置開始串流；**串流中再點另一台裝置就會加入成第二台**（最多 2 台），再點一次即移除。第二台連線失敗只會被移除，不影響第一台。
- **已配對的 HomePod 立體聲組**：App 會提醒「已配對立體聲喇叭」。左右聲道已由組內兩顆負責，不能再拆。
- **兩台都是單顆喇叭**：串流中會出現「聲道設定」卡片，可選「兩台都播立體聲」、「A 左 ／ B 右」或「A 右 ／ B 左」。原理是各自只送一邊的聲道（左右聲道都填同一邊的內容），兩台由 NTP 時間同步一起播放。
- 兩台之間可能有些微延遲差，可用「設定 → 音訊延遲」微調。

## 音量（v1.2.5）
- 預設音量 25%；每次連線成功都會套用「上一次使用的音量」並取消靜音。
- 連線成功不會自動開始播放：狀態先是「已連線」，擷取到聲音才算「播放中」。
- 系統音量鍵 / 音量滑桿：以 MediaSession 遠端音量直接調整接收端音量（只接管音量，忽略 play / pause / stop）。

## HyperIsland（v1.2.5：純顯示，沒有控制按鈕）
- 顯示：連線狀態（連線中 / 已連線 / 播放中 / 已中斷）、目前音量、連接的裝置圖示（HomePod / HomePod mini / Apple TV / AirPlay）與名稱。
- 不再提供任何 HyperIsland 按鈕（系統不會把按鈕事件交給本 App）。下拉通知欄的一般通知仍保留音量 − / ＋ 與中斷。

## HomePod / 立體聲組（AirPlay 2）
HomePod 會自動走 AirPlay 2。立體聲組不再經過 Apple TV 或個別連成員，改成和 Base 相同的做法：直連組內主機（igl），在 SETUP 帶上 `groupUUID`（mDNS 的 gid）與 `groupContainsGroupLeader`（gcgl），由 HomePod 自己把聲音分給兩顆，保持左右聲道。使用前請在「家庭」App → 該裝置設定 → 允許喇叭與電視存取，改成「所有人」或「同一網路上的任何人」，並且不要設密碼。

## 裝置分組（v1.2.2，對照 Base 修正）
- 只有 HomePod 會被分組（分組鍵 tsid → gid → pgid）；Apple TV、其他喇叭、Mac 一律單獨一台，不再被併進 HomePod 群組，也不再被當成「組內主機」。
- 同組 ≥ 2 顆 HomePod 時，清單多一個「HomePod 組合」項目，連線時直連每一顆 HomePod（transient 配對，免配對碼），不經過 Apple TV。

## 配對碼 / 密碼
- 裝置要求配對碼時（Apple TV / 與 Apple TV 組合的 HomePod），App 會送 `/pair-pin-start` 讓電視顯示 4 位數配對碼，輸入後走完整 HomeKit 配對（`X-Apple-HKP: 3`，Pair-Setup M1~M6）再 Pair-Verify，做法與 Base 相同；最多重試 3 次。
- 配對成功後身分會存在本機，之後連線只做 Pair-Verify，不必再輸入配對碼（若失效會自動清除並重新配對）。
- 固定碼 3939 的 transient 配對（`HKP: 4`）仍用於不需螢幕配對碼的 HomePod。
- 設有密碼的 AirPlay 1 裝置會跳出密碼輸入框。

## 診斷日誌（設定 → 診斷）
連線失敗時可開啟「日誌記錄」：先顯示同意書（收集項目、用途、權益），同意後才開始記錄之後的連線過程。
- 只存在本機，不自動上傳；「匯出日誌檔」會用系統檔案選擇器存成 `HyperCast_日期_時間_地點.log`。
- IP 末段、MAC、UUID 會遮蔽；不記錄配對碼、金鑰、音訊內容、Wi-Fi 名稱。
- 地點為選用的「概略位置」（約 1 公里精度，只在連線時讀一次）；未授權則檔名為「未知地點」。

## 已知限制
- 只能擷取允許被錄製的 App 音訊（部分 App 與 DRM 內容會是靜音）。
- 需要 HomeKit 正式配對的裝置、以及有密碼的 AirPlay 2 接收端尚未支援。
- 左右聲道分配尚未在實機驗證；若連線失敗，請開啟診斷日誌並匯出。
