# 功法与世界接线

熬药卷由本仓目录提供。玩家通过 Technique 服务学卷和施放，`meridian_brewing:block_work`
在进式 1 秒后接管视线命中的巫釜，此后按目录周期推进 30 次，每次推进一秒规则时间。
视线命中声明巫釜正下方的火源（`#meridian_brewing:brew_fires` 或原版 `#minecraft:campfires`）时，
目标按它上面那只釜算，所以熬制中低头加炭、拨灰不会被当成换了目标；命中旁边的其他方块仍判视线丢失。
熬制全部属于可被僵直打断的 31 秒起手，随后收势 1 秒。启动时在 Technique 服务就绪通知中校验
周期、持续时间、进式和三阶段声明；它们必须与 `BrewCauldron.BREW_SECONDS` 一致，否则中止装载。

迟滞由 Technique 对原始事件边界缩放，巫釜的熬制进度与投料时窗同步放慢。
结构、目标、距离、权限、持物或火种失效时，处理器返回结构化拒绝原因。
`CAST_ENDED` 释放巫釜接管并删除这次施放的活动与幂等记录，覆盖正常结束、失败、僵直、死亡、卸载和下线。
中断回到生药时保留药材与浸泡累计，火候累计和投料时窗清零；已支付的功法费用由上游保持。

熬药目录的功法费用为空，真元按锅另算（`BrewQiPayment`）。`block_work` 接管巫釜前预检：
经 Human 的 `meridian_environment_host` 查施法者所在区域，取存量与 `spare_absorption_per_second × 30` 的小者，
加施法者已开经脉腔体余额，不足一锅返回 `qi.short_at_start`。每次推进先按 `BrewQiTally.nextShare()` 付本秒份额：
`draw` 从区域取（共享吸收速率窗口，取走的量进默认释放账户，下一窗口散回同一区域），不足部分通过 `BrewBodyHost.activity`
从已开经脉按余额比例扣，`released_qi` 等于扣减合计，满足 Framework 守恒。两边仍不够时以 `technique.qi_short` 中断。
`draw_id` 为 `meridian_brewing:brew/<本锅随机 UUID>/<推进事件 ID>`，同一秒重放不重复取。
施法者身体暂停或解绑按付不起处理。只有正式熬药式推进收费；`BrewQiTally` 只在内存中，停服打断进行中的熬制。

熬药目录绑定 `human:adult` 的手臂肌肉，实际迟滞会延长熬药。
Human 统一拦截施放中主手，副手仅对目录声明的釜与火标签放行，下料、添水、加炭、拨灰和灭火照常执行。
添水与灭火共用容器返还：生存玩家最后一桶水在原手位换成空桶，背包满时多余容器掉落。
空气、实体和未声明方块不享受副手豁免；本仓方块不再重复维护施放门禁。

熬药式在 0 ms 挂表现效果 `meridian_brewing:witch_brew`，Human 播 32 s 的 PA 剪辑 `witch_brew`，收势结束停。
熬制中副手对釜下料、添水，或对釜下的火加炭、拨灰、灭火，每成功一次，`BrewCastAnimations` 给玩家叠一段
0.6 s 的左臂动作 `witch_brew_feed_off`，熬药那段照播；被拒绝的操作不叠。

`BrewSoundPlayback` 集中保存五秒周期的搅、扇、闻时刻和音量音高，全部使用原版声音事件。
每次推进预约下一步内的声音，子秒偏移按迟滞比例延长；施放结束或打断就丢弃剩余播放任务。
客户端听感、动画对齐未测。

`use_brew` 保留普通饮用、喷溅入口，并按 `BrewTechniqueParameters` 执行服、敷、掷、泼、噀。
强度品质、强度倍率、持续倍率和反胃倍率各自保存；参数来自内容目录，药封仍按药瓶原品质。
物品种类、品质及物理用法必须与服务端持物事实一致。效果幂等记录按施放分组，结束时清理。
玩家学习 `meridian_witch:guard` 后再学 `meridian_witch:seed`，通过正式 `TechniqueService.cast` 使用五式。
纯数据行药诀包只提供目录、语言与声音，没有 Java 入口；女巫出生学卷与 AI 选药另行实现。

服药升一档强度，顶档使用目录倍率；敷药比较两臂和两腿可修复伤势，平局取两臂。
敷药在 0 ms 的 `meridian_brewing:anoint_start` 起手定组，并按组播 `witch_anoint_arms` 或 `witch_anoint_legs`：
玩家经 PA，生物经 PA 生物附属（硬依赖），`CAST_ENDED` 时停。准备声音与最终药封共用该组；目录没写起手那条时，
第一条敷药 `use_brew` 补定组但不播动作。生存玩家效果成功后原手留空瓶，掷引把瓶掷出；
创造玩家和生物不扣瓶。打断未到效果边界不消费。反胃拦服药和噀催的口服动作。

掷引通过 `HELD_SPLASH_BREW` 射出真实原版药瓶，读取显式目标 UUID 或出手视线，合法目标取脚下，
无目标取视线落点。显式目标仍受射程、存活和视线约束；无散布弹道修正药瓶先重力后位移的积分顺序。
出手时冻结头颈、躯干或腿偏向；飞行元数据只在内存中，卸载清除。部位偏向权重暂为 4。
泼引按目录距离、角度、目标上限和视线筛选；噀催按同来源同药种筛选，隔墙可催。
残药复用 `ResidueStore`，同源同药同部位覆盖，剂量低于 0.05、牛奶、死亡、卸载、停服清除。
枯竭残药随当前肾脏档衰减，恢复类功法使半衰期除以 3；肾脏档和是否在施恢复式按目标每秒读一次身体，目标起式（`addCastCheck`）、收式（`CAST_ENDED`）或新下一条残药时当 tick 作废重读，肾脏档变化最多晚 1 秒生效；催发强度乘剩余剂量与目录倍率。
药效声锚在中药者处，下引每目标一声；动作声经功法声音入口按目录时刻发包。
用药成功那一刻 `BrewCastParticles` 在施放者处发这种药本色的原版 `entity_effect` 粒子，玩家和生物同一处：
服、敷腰间一圈淡粒子（透明度 0.35），泼引照目录射程和扇角在胸口高度铺一片，噀催从嘴前顺视线喷 3 m，
掷引在出手处溅一团（落地另有原版喷溅药水粒子）。颜色取 `BrewKind.colorRgb()`，不加贴图。

落地枯萎对玩家使用 `level.mayInteract(player, pos)` 与 `player.mayBuild()`；生物来源只看 `mobGriefing`。
不使用重生散布半径仿造出生点保护，专用服的保护由原版交互判定处理。

验证入口：`flock "/tmp/kizuna-gradle.lock" "./gradlew" test build :gametest:runGametest --no-daemon`。
`BrewingCastGameTest` 学会生产熬药卷后走正式 `TechniqueService.cast`；玩家由 Human 正式登录入口绑定身体，
学会、起手与收势通过 Framework 二进制事务，世界方块与玩家物品使用真实 Fabric 对象。
内存网络连接只代替传输层，并推进正式连接 tick 与心跳；不登记替代身体宿主。
`WitchPresentationGameTest` 查行药诀五式和熬药式的剪辑长度、效果帧与施放对得上，
玩家泼引、敷药（两臂、两腿各一例）起手当 tick 收到 PA 播放、收势结束才收到停止，
泼引、服药的药色粒子在用药那一刻才发出；女巫放泼引、敷药时 PA 生物附属登记对应剪辑、收势后撤掉，
旁边的玩家收到药色粒子。`BrewingCastGameTest` 另查熬药整段动作和三次副手操作各叠一段下料动作。
剪辑长度等于施放全长，PA 播完自然到期和收势结束会各发一个停止包，先后不固定，测试只要求「开头播一次、之后只有停止」。
真客户端截图 `WitchCastClientTest`（`-PclienttestOnly=fling`）让玩家和一只真炁女巫各放一次泼引，拍起手到一半、泼出、粒子散开、收势后四个时刻。
