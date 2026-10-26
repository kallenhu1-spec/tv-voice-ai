# 国内电视视频应用的“直达”入口

这些跳转方式都不是官方公开的接口，是在一台索尼 KD-75X7800F（国行，Android 8）上用 `tools/probe-search.sh` 逐个试出来的：电视打开应用 → 记录停在哪个界面 → 截图确认。应用一更新就可能失效，欢迎提 PR 补充或更正（请写上应用版本和测试日期）。

`<词>` 表示搜索关键词；“需要编码”指要做 URL 编码（`%E5%BA%86…`）。

| 应用 | 包名 | 跳转方式（adb shell am start …） | 效果 | 备注 |
|---|---|---|---|---|
| 云视听极光（腾讯） | com.ktcp.video | `-a com.tencent.qqlivetv.open -d "tenvideo2://?action=9&search_key=<词>" com.ktcp.video` | 搜索结果 | 关键词**不要**编码，编码后搜索框显示乱码 |
| 银河奇异果（爱奇艺） | com.gitvdemo.video | `-a com.gitvdemo.video.action.ACTION_SEARCHRESULT -d "gala://page?keyword=<词>" -p com.gitvdemo.video --es keyword "<词>"` | 搜索结果 | data 里的词需要编码 |
| CIBN 酷喵（优酷） | com.cibn.tv | `-a android.intent.action.VIEW -d "cibntv_yingshi://search?keyword=<词>" com.cibn.tv` | 搜索页，已填好关键词 | 需要编码 |
| 芒果TV | com.hunantv.market | `-a android.intent.action.VIEW -d "mgtvapp://search/ott/searchInput?searchKey=<词>" com.hunantv.market` | 搜索结果 | 需要编码；参数名 `searchKey` 来自安装包里的字符串 |
| 云视听小电视（B站） | com.xiaodianshi.tv.yst | `-a android.intent.action.VIEW -d "yst://com.xiaodianshi.tv.yst?type=11&keyword=<词>" com.xiaodianshi.tv.yst` | 搜索页 | 需要编码 |

### 云视听小电视（B站）的其他页面

`yst://com.xiaodianshi.tv.yst?type=<编号>`，编号是逐个试出来的：

| type | 页面（打开后的界面类名） |
|---|---|
| 1 | 首页 |
| 3 | 播放指定视频（另需 `avId=` 或 `seasonId=`，来自安装包字符串，未实测） |
| 10 | 历史记录（`ui.history.VideoHistoryActivity`） |
| 11 | 搜索（`ui.search.SearchActivity`） |
| 12 | 我的收藏（`ui.favorite.FavoriteActivity`） |
| 13 | 连续播放（`ui.continuous.activity.CtsActivity`） |
| 19 | 大会员（`activity.VipActivity`） |
| 21 | 番剧时间表（`ui.bangumi.timetable.TimeTableActivity`） |
| 22 | 直播列表（`ui.main.live.EgLiveListActivity`） |
| 27 | 优惠券（`ui.coupon.Coupon2Activity`） |
| 29 | 排行榜（`ui.rank.RankLevelActivity`） |

试过但无效：`yst://com.xiaodianshi.tv.yst/favorite`、`yst2://…/favorite`（都回到首页）；“关注”“我的账号”页面在 type 12–30 里没找到。

### 怎么找新的入口

1. `adb shell dumpsys package <包名>`，看 “Activity Resolver Table” 里登记的 action 和 scheme。
2. 从安装包的 dex 里搜字符串（例如 `mgtvapp://`、`searchKey`、`type=`），能找到应用内部用的跳转地址和参数名。
3. 用 `tools/probe-search.sh` 的写法逐个试，每次截图 + 记录 `mResumedActivity`。有些应用禁止截屏，以界面类名为准。
