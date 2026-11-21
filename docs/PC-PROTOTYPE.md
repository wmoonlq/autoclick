# outoclick 路 瀹夊崜鑷姩鐐瑰嚮鍣?
鐙珛 Android App锛氬湪鎵嬫満涓婇璁惧浐瀹氬潗鏍囩殑鐐瑰嚮搴忓垪锛岀敱鏃犻殰纰嶆湇鍔″洖鏀俱€?**涓嶉渶瑕佺數鑴戙€佷笉闇€瑕?ADB銆佷笉闇€瑕?Root銆?*

> 褰撳墠鐗堟湰锛歁VP `0.1.0`锛坉ebug APK锛?
## 鑳藉仛浠€涔?
- **鎮诞鍑嗘槦鍙栫偣**锛氭墦寮€鐩爣 App锛屾嫋鍔ㄥ崄瀛楀噯鏄熷埌鎸夐挳涓婏紝鐐广€岃褰曘€嶅嵆鐢熸垚涓€姝ュ潗鏍?- **鑴氭湰缂栨帓**锛氱偣鍑?/ 闀挎寜 / 婊戝姩 / 绛夊緟锛涙瘡姝ュ彲璁惧欢鏃躲€佸惎鐢ㄧ姸鎬侊紱鑴氭湰鍙惊鐜?N 娆?- **鎮诞鐞冩帶鍒?*锛氬紑濮?/ 鏆傚仠 / 缁х画 / 鍋滄锛涘垏鍒颁换浣?App 閮借兘鎿嶄綔
- **鏈満瀛樺偍**锛氳剼鏈槸绾?JSON锛屾敮鎸佸鍒躲€佸垹闄ゃ€佸壀璐存澘瀵煎叆瀵煎嚭
- **鍓嶅彴鏈嶅姟**锛氳繍琛岀姸鎬佸父椹婚€氱煡锛岄伩鍏嶈绯荤粺闅忔墜鍥炴敹
- **娣辫壊鐣岄潰**锛氭墜鏈虹鍘熺敓 View锛屾棤绗笁鏂?UI 妗嗘灦

## 蹇€熷紑濮?
### 鐩存帴瀹夎 APK

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

鎴栬€呮妸 APK 鎷峰埌鎵嬫満涓婃墜鍔ㄥ畨瑁咃紙闇€瑕佸厑璁搞€屽畨瑁呮湭鐭ユ潵婧愬簲鐢ㄣ€嶏級銆?
### 浠庢簮鐮佹瀯寤?
鍓嶇疆锛?
- JDK 17 鎴?21
- Android SDK锛歚platforms;android-36`銆乣build-tools;35.0.0+`
- Gradle 8.13锛堝伐绋嬫病鏈夊唴缃?wrapper锛?
```powershell
$env:JAVA_HOME='D:\JDKSS\Jdks\jdk-21.0.12.1+1'
$env:ANDROID_HOME='E:\AndroidSdk'
cd android
E:\gradle\gradle-8.13\bin\gradle.bat assembleDebug
```

`android/local.properties` 閲岀殑 `sdk.dir` 鎸囧悜浣犵殑 SDK 鐩綍锛堣鏂囦欢宸茶 gitignore锛夈€?
浜х墿锛?
```
android/app/build/outputs/apk/debug/app-debug.apk
```

## 浣跨敤娴佺▼

1. 鎵撳紑 App锛屽湪銆屽噯澶囥€嶅崱鐗囬噷渚濇寮€鍚細
   - **鏃犻殰纰嶆湇鍔?*锛氳缃?鈫?鏃犻殰纰?鈫?宸蹭笅杞界殑搴旂敤 鈫?outoclick 鐐瑰嚮鎵ц鍣?   - **鎮诞绐?*锛氬厑璁告樉绀哄湪鍏朵粬搴旂敤涓婂眰
   - **閫氱煡**锛圓ndroid 13+锛夛細鍓嶅彴鏈嶅姟鐘舵€?2. 鍦ㄣ€岃剼鏈€嶉噷鏂板缓锛屾垨鐩存帴鐢ㄨ嚜甯︾殑绀轰緥鑴氭湰銆?3. 鐐广€屽惎鍔ㄦ偓娴獥銆嶏紝App 浼氶€€鍒板悗鍙帮紝灞忓箷涓婂嚭鐜?**OC** 鎮诞鐞冦€?4. 鍒囧埌鐩爣 App锛岀偣鎮诞鐞?鈫掋€屽彇鐐广€嶏細
   - 鎷栧姩鍑嗘槦鍒扮洰鏍囦綅缃?鈫掋€岃褰曠偣鍑汇€?   - 璁板綍婊戝姩锛氬厛鎶婂噯鏄熸斁鍒拌捣鐐?鈫掋€岃捣鐐广€嶁啋 绉诲姩鍒扮粓鐐?鈫掋€岀粓鐐广€?5. 闇€瑕佽繍琛岃剼鏈椂锛岀偣鎮诞鐞?鈫掋€屽紑濮嬨€嶃€傝繍琛屼腑鍙殏鍋溿€佺户缁€佸仠姝€?6. 鍥炲埌 App 鍙互寰皟姣忎竴姝ョ殑鍧愭爣銆佹椂闀裤€佸欢鏃跺拰寰幆娆℃暟銆?
## 鑴氭湰鏍煎紡

姣忓彴鎵嬫満鏈湴淇濆瓨涓?`files/scripts/<id>.json`锛屼篃鍙互浠?App 瀵煎嚭鍒板壀璐存澘锛?
```json
{
  "schemaVersion": 1,
  "id": "a1b2c3d4e5",
  "name": "姣忔棩绛惧埌",
  "repeat": 1,
  "steps": [
    { "type": "TAP", "x": 540, "y": 1200, "durationMs": 60, "delayAfterMs": 800, "enabled": true },
    { "type": "LONG_PRESS", "x": 300, "y": 900, "durationMs": 800, "delayAfterMs": 300, "enabled": true },
    { "type": "SWIPE", "x": 540, "y": 1800, "x2": 540, "y2": 900, "durationMs": 300, "delayAfterMs": 300, "enabled": true },
    { "type": "WAIT", "durationMs": 1500, "delayAfterMs": 0, "enabled": true }
  ]
}
```

鍧愭爣鏄?*灞忓箷鐗╃悊鍍忕礌**锛屽師鐐瑰湪宸︿笂瑙掋€?
## 鏉冮檺涓庡畨鍏?
| 鏉冮檺 | 鐢ㄩ€?| 鏄惁璇诲彇鍐呭 |
|---|---|---|
| 鏃犻殰纰嶆湇鍔?| 閫氳繃 `dispatchGesture` 娉ㄥ叆鐐瑰嚮/婊戝姩 | 鍚︼紝`canRetrieveWindowContent=false` |
| 鎮诞绐?| 鎮诞鐞?+ 鍑嗘槦鍙栫偣 | 鍚?|
| 閫氱煡 | 鍓嶅彴鏈嶅姟鐘舵€?| 鍚?|

- 鎵€鏈夎剼鏈笌杩愯鐘舵€佸彧瀛樺湪鏈満锛孉pp 涓嶈仈缃戙€?- 鏃犻殰纰嶆湇鍔″彧鍋氭墜鍔挎敞鍏ワ紝涓嶈鍙栧睆骞曞唴瀹癸紝涓嶄笂浼犱换浣曟暟鎹€?- 鍋滄鏄€屼笉鍐嶆淳鍙戜笅涓€姝ャ€嶏紝宸茬粡鍦ㄦ墽琛岀殑涓€娆℃墜鍔匡紙閫氬父 60鈥?00ms锛変細鑷劧缁撴潫锛屼笉浼氳ˉ鐐广€?
## 鍘熺悊

```
MainActivity        鑴氭湰鍒楄〃 / 姝ラ缂栬緫 / 鏉冮檺寮曞
OverlayService      鎮诞鐞冦€侀潰鏉裤€佸噯鏄熴€佸墠鍙伴€氱煡
ClickAccessibilityService   dispatchGesture(TAP / LONG_PRESS / SWIPE)
Runner              鍗忕▼椤哄簭鎵ц锛屽惊鐜€佸欢鏃躲€佹殏鍋?鍋滄
ScriptStore         filesDir/scripts/*.json
```

- 鐐瑰嚮锛歚GestureDescription.StrokeDescription(path, 0, 60ms)`
- 闀挎寜锛氬悓鐐癸紝鏃堕暱 800ms
- 婊戝姩锛歚moveTo 鈫?lineTo`锛岄粯璁?300ms
- 绛夊緟锛歚delay(ms)`锛屽彲琚殏鍋?鍋滄鎵撴柇
- 杩愯寰幆锛歚delayAfterMs` 鍦ㄦ瘡姝ヤ箣鍚庢墽琛?
## 宸茬煡闄愬埗

- **鍥哄畾鍧愭爣**锛氱洰鏍?App 鏀圭増銆佹崲鍒嗚鲸鐜囥€佹敼瀛椾綋澶у皬鍚庨渶瑕侀噸鏂板彇鐐广€?- **浠呯珫灞?*锛氭殏涓嶅鐞嗘棆杞悗鐨勫潗鏍囨崲绠椼€?- **鏃犲浘鍍忚瘑鍒?*锛氫笉浼氥€屾壘鎸夐挳銆嶏紝鍙寜鍧愭爣鐐广€?- **鎭睆/閿佸睆**锛氭棤闅滅鎵嬪娍鍦ㄩ攣灞忕姸鎬佷笅鏃犳硶鎿嶄綔鐩爣 App銆?- **绯荤粺瀹夊叏鐣岄潰**锛氶儴鍒嗙郴缁熷脊绐椼€佸畨鍏ㄩ敭鐩樸€佹敮浠樼被椤甸潰鍙兘鎷掔粷娉ㄥ叆銆?- **鍘傚晢淇濇椿**锛歁IUI / HyperOS / ColorOS 绛夊彲鑳芥潃鍚庡彴锛屽缓璁妸 App 鍔犲叆鐢垫睜鐧藉悕鍗曘€?- **鍙嶄綔寮婇闄?*锛氭父鎴?骞冲彴鍙兘妫€娴嬭嚜鍔ㄥ寲鎿嶄綔锛岃处鍙烽闄╃敱浣跨敤鑰呮壙鎷呫€?
## 椤圭洰缁撴瀯

```
android/                    鐙珛 Android 宸ョ▼锛堜富浜や粯鐗╋級
  app/src/main/java/com/outoclick/app/
    MainActivity.kt         鑴氭湰鍒楄〃 / 姝ラ缂栬緫 / 鏉冮檺寮曞
    OverlayService.kt       鎮诞鐞冦€侀潰鏉裤€佸噯鏄熴€侀€氱煡
    ClickAccessibilityService.kt  鎵嬪娍娉ㄥ叆
    Runner.kt               椤哄簭鎵ц / 寰幆 / 鏆傚仠鍋滄
    Model.kt / Store.kt     鑴氭湰妯″瀷涓庢湰鏈?JSON 瀛樺偍
app/                        鏃╂湡鐨?Windows + ADB 鍘熷瀷锛圥ython锛岄潪涓讳氦浠樼墿锛?docs/PC-PROTOTYPE.md        PC 鍘熷瀷璇存槑
docs/FEASIBILITY-REVIEW.md  瑙勬牸鍙鎬ц瘎瀹?SPEC.md                     PC 鍘熷瀷瑙勬牸锛坴0.4锛夛紝瀹夊崜鐗堢殑绾︽潫鍙弬鑰?```

## 寮€鍙?
```powershell
# 鍙紪璇戯紝涓嶅畨瑁?cd android
E:\gradle\gradle-8.13\bin\gradle.bat assembleDebug

# 瀹夎骞剁湅鏃ュ織
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s outoclick AndroidRuntime
```

璋冭瘯鏃犻殰纰嶆湇鍔℃椂锛屾敼瀹屼唬鐮侀噸鏂板畨瑁呭悗闇€瑕佸湪绯荤粺璁剧疆閲?*鍏抽棴鍐嶆墦寮€**涓€娆℃棤闅滅鏈嶅姟锛屾湇鍔℃墠浼氱粦瀹氭柊鐗堟湰銆?
## 鏁呴殰鎺掓煡

| 鐜拌薄 | 澶勭悊 |
|---|---|
| 鎮诞鐞冧笉鍑虹幇 | 纭銆屾樉绀哄湪鍏朵粬搴旂敤涓婂眰銆嶅凡鍏佽锛涘湪 App 閲岄噸鏂扮偣銆屽惎鍔ㄦ偓娴獥銆?|
| 鐐广€屽紑濮嬨€嶆病鍙嶅簲 | 鏃犻殰纰嶆湇鍔℃湭寮€鍚紝鎴栨湇鍔¤绯荤粺鏉€鎺夛紱閲嶆柊杩涜缃紑鍏充竴娆?|
| 鐐瑰嚮浣嶇疆鍋忕Щ | 鍧愭爣鏄睆骞曠墿鐞嗗儚绱狅紱鐢ㄥ噯鏄熼噸鏂板彇鐐癸紝娉ㄦ剰鐘舵€佹爮/鎵嬪娍瀵艰埅鍖哄煙 |
| 杩愯涓€浼氬効灏卞仠 | 鍘傚晢鐪佺數绛栫暐鏉€鎺夋湇鍔★紱鍔犲叆鐢垫睜鐧藉悕鍗曪紝淇濇寔鍓嶅彴閫氱煡 |
| 鍙栫偣闈㈡澘鎸′綇鐩爣 | 鎷栧姩鎮诞鐞冩崲涓綅缃紝鎴栫偣銆岄殣钘忋€嶅啀鐐圭悆灞曞紑 |
| 瀹夎澶辫触 | 纭鍏佽鏈煡鏉ユ簮锛涙垨 `adb install -r` 瑕嗙洊瀹夎 |

## 涓嬩竴姝?
- 褰曞埗妯″紡锛堣褰曠湡瀹炴墜鎸囪建杩圭敓鎴愭楠わ級
- 鑴氭湰鍒嗙粍寰幆 / 鏉′欢姝ラ
- 瀵煎叆瀵煎嚭鏂囦欢锛堢幇鍦ㄨ蛋鍓创鏉匡級
- 妯睆涓庢棆杞潗鏍囨崲绠?- 杩愯鏃ュ織涓庡け璐ユ埅鍥撅紙闇€瑕?MediaProjection锛?- 璁″垝浠诲姟锛堟瘡澶╁畾鏃舵墽琛岋級

