(function () {
  "use strict";

  var STORAGE_KEY = "frostfall-game-v1";
  var root = document.getElementById("app");
  var modalRoot = document.getElementById("modal-root");
  var toastRoot = document.getElementById("toast-root");
  var items = [];
  var itemById = Object.create(null);
  var cart = Object.create(null);
  var shopId = "supermarket";
  var marketSearch = "";
  var marketCategory = "全部";
  var marketSort = "推荐";
  var warehouseSearch = "";
  var setup = { name: "林见秋", origin: "office", difficulty: "normal", talents: ["囤货大师"] };
  var saveBackend = "browser";

  var shops = [
    { id: "supermarket", name: "城北大型超市", icon: "🏬", place: "步行 25 分钟", hours: 2, kind: "实体", freight: 90 },
    { id: "wholesale", name: "永丰批发市场", icon: "📦", place: "城西货运区", hours: 3, kind: "实体", freight: 160 },
    { id: "online", name: "星海网上商城", icon: "📱", place: "在线配送", hours: 0.5, kind: "网购", freight: 0 },
    { id: "pharmacy", name: "安宁连锁药店", icon: "✚", place: "社区药房", hours: 1.5, kind: "实体", freight: 80 },
    { id: "hardware", name: "老周五金店", icon: "🔩", place: "南街五金城", hours: 2, kind: "实体", freight: 100 },
    { id: "appliances", name: "北辰家电商城", icon: "🔌", place: "中央商圈", hours: 2.5, kind: "实体", freight: 180 },
    { id: "outdoor", name: "山径户外用品", icon: "⛺", place: "运动街", hours: 2, kind: "实体", freight: 110 },
    { id: "energy", name: "远航能源站", icon: "⛽", place: "东郊物流园", hours: 3, kind: "实体", freight: 240 },
    { id: "farmers", name: "清河农贸市场", icon: "🥬", place: "河畔街区", hours: 2, kind: "实体", freight: 100 },
    { id: "secondhand", name: "旧物集市", icon: "♻️", place: "旧城广场", hours: 2, kind: "实体", freight: 70 }
  ];

  var shopLabels = {
    supermarket: "大型超市", wholesale: "批发市场", online: "网上商城",
    pharmacy: "药店", hardware: "五金店", appliances: "家电商城",
    outdoor: "户外用品店", energy: "能源商店", farmers: "农贸市场", secondhand: "二手市场"
  };

  function shopsFor(category) {
    var food = ["主食", "肉蛋", "蔬果", "调味", "饮水", "零食", "饮料"];
    var daily = ["日用", "清洁", "个人护理"];
    var tools = ["工具", "建材", "种植"];
    var warm = ["保暖", "服装"];
    var medicine = ["医疗"];
    var energy = ["能源", "电器"];
    var comfort = ["娱乐"];
    var result = ["online"];
    if (food.indexOf(category) >= 0) result = result.concat(["supermarket", "wholesale", "farmers"]);
    if (daily.indexOf(category) >= 0) result = result.concat(["supermarket", "wholesale", "pharmacy", "hardware", "secondhand"]);
    if (tools.indexOf(category) >= 0) result = result.concat(["hardware", "outdoor", "secondhand", "farmers"]);
    if (warm.indexOf(category) >= 0) result = result.concat(["outdoor", "supermarket", "secondhand"]);
    if (medicine.indexOf(category) >= 0) result = result.concat(["pharmacy", "outdoor"]);
    if (energy.indexOf(category) >= 0) result = result.concat(["energy", "appliances", "hardware", "secondhand"]);
    if (comfort.indexOf(category) >= 0) result = result.concat(["supermarket", "outdoor", "secondhand"]);
    return Array.from(new Set(result));
  }

  function addItem(id, name, category, icon, price, weight, volume, shelf, description, effect, stock) {
    var item = {
      id: id, name: name, category: category, icon: icon, price: price,
      weight: weight, volume: volume, shelf: shelf, description: description,
      effect: effect || {}, stock: stock || Math.max(4, Math.min(70, Math.round(180 / Math.sqrt(price + 1)))),
      shops: shopsFor(category), unit: "件"
    };
    items.push(item);
    itemById[id] = item;
  }

  function addFood(id, name, category, icon, price, weight, shelf, hunger, thirst, morale, cooked, description) {
    addItem(id, name, category, icon, price, weight, weight * 0.82, shelf, description,
      { kind: "food", hunger: hunger, thirst: thirst, morale: morale || 0, raw: !cooked },
      Math.max(5, Math.min(100, Math.round(300 / (price + 2)))));
  }
  function addDrink(id, name, icon, price, weight, shelf, thirst, description) {
    var effect=id==="water-purification"?{kind:"purify",thirst:0}:{kind:"drink",thirst:thirst,morale:id==="tea"||id==="coffee"?4:id==="cocoa"?7:0};
    addItem(id, name, "饮水", icon, price, weight, weight * 0.8, shelf, description, effect, 20);
  }
  function addResource(id, name, category, icon, price, weight, volume, description, effect, stock) {
    addItem(id, name, category, icon, price, weight, volume, 9999, description, effect || {}, stock);
  }

  [
    ["rice","大米","主食","🍚",6,1,365,0,0,0,false,"需要水和燃料煮熟，是稳定的长期主粮。"],
    ["flour","面粉","主食","🌾",8,1,240,0,0,0,false,"可制作面饼、面包和高热量口粮。"],
    ["noodles","挂面","主食","🍜",9,0.45,300,0,0,0,false,"轻便耐放，煮熟后恢复饱食。"],
    ["instant-noodles","方便面","主食","🍜",5,0.13,240,19,2,-1,true,"开水即食，省燃料但营养一般。"],
    ["oats","燕麦片","主食","🥣",13,0.5,300,0,0,0,false,"富含能量，适合做热粥。"],
    ["bread","切片面包","主食","🍞",12,0.45,8,24,2,1,true,"饱腹方便，保质期短。"],
    ["crackers","压缩饼干","主食","🧇",18,0.25,730,32,-2,-2,true,"高热量应急口粮，长期食用影响心情。"],
    ["potato","土豆","蔬果","🥔",4,0.3,45,0,0,0,false,"耐储存，可烤制或种植。"],
    ["sweet-potato","红薯","蔬果","🍠",5,0.3,30,0,0,0,false,"可烤制，温热后更有饱腹感。"],
    ["corn","玉米","主食","🌽",6,0.35,90,0,0,0,false,"可煮食，也可作为种植作物。"],
    ["millet","小米","主食","🟡",8,0.5,365,0,0,0,false,"熬粥方便，寒天暖胃。"],
    ["buckwheat","荞麦","主食","🌾",12,0.5,365,0,0,0,false,"高纤谷物，适合长期轮换口粮。"],
    ["instant-rice","自热米饭","主食","🍱",28,0.38,365,35,4,2,true,"无需炉灶即可加热，适合停电时食用。"],
    ["emergency-ration","应急能量餐","主食","🥫",34,0.3,900,42,1,0,true,"高能量密封口粮，适合外出携带。"],
    ["pork","猪肉","肉蛋","🥩",26,0.5,3,0,0,0,false,"新鲜肉类，需尽快冷藏或烹饪。"],
    ["beef","牛肉","肉蛋","🥩",48,0.5,4,0,0,0,false,"高蛋白鲜肉，烹饪后恢复更多体力。"],
    ["chicken","鸡肉","肉蛋","🍗",19,0.45,3,0,0,0,false,"适合炖煮，保存时间短。"],
    ["fish","鲜鱼","肉蛋","🐟",24,0.4,2,0,0,0,false,"营养丰富，必须冷藏或尽快加工。"],
    ["eggs","鸡蛋","肉蛋","🥚",14,0.35,14,0,0,0,false,"可煮食或加入料理，营养均衡。"],
    ["luncheon-meat","午餐肉罐头","肉蛋","🥫",22,0.34,1095,31,0,1,true,"开罐即食，长期储存稳定。"],
    ["beef-can","红烧牛肉罐头","肉蛋","🥫",32,0.38,1460,40,0,2,true,"高蛋白即食食品，份量扎实。"],
    ["fish-can","鱼肉罐头","肉蛋","🐟",18,0.22,1095,25,0,1,true,"可直接食用，也能加入炖菜。"],
    ["pork-can","梅菜扣肉罐头","肉蛋","🥫",28,0.38,1095,38,0,1,true,"油脂充足，寒冷日提供高热量。"],
    ["dried-meat","风干肉条","肉蛋","🥓",37,0.18,240,26,-1,0,true,"轻量高蛋白探索口粮。"],
    ["milk-powder","全脂奶粉","肉蛋","🥛",46,0.4,730,22,16,1,true,"补充营养，冲调需要净水。"],
    ["beans","黄豆","肉蛋","🫘",11,0.5,365,0,0,0,false,"可烹饪或播种，蛋白质来源。"],
    ["tofu","北豆腐","肉蛋","⬜",7,0.3,4,0,0,0,false,"新鲜豆制品，适合加入汤锅。"],
    ["peanuts","花生","零食","🥜",16,0.25,240,18,0,2,true,"高热量零食，少量恢复精神。"],
    ["cabbage","大白菜","蔬果","🥬",5,0.7,12,0,0,0,false,"可烹饪、发酵或种植。"],
    ["carrot","胡萝卜","蔬果","🥕",6,0.25,18,0,0,0,false,"耐储根茎菜，可种植。"],
    ["tomato","西红柿","蔬果","🍅",8,0.3,7,0,0,0,false,"新鲜蔬菜，适合烹饪或直接食用。"],
    ["onion","洋葱","蔬果","🧅",7,0.2,35,0,0,0,false,"保存较久，为料理增加风味。"],
    ["mushroom","香菇","蔬果","🍄",13,0.15,5,0,0,0,false,"鲜菇需及时处理，干制后更耐储。"],
    ["spinach","菠菜","蔬果","🥬",7,0.25,4,0,0,0,false,"富含维生素，适合做汤。"],
    ["apple","苹果","蔬果","🍎",8,0.22,30,9,10,3,true,"补充水分和精神，储存期适中。"],
    ["orange","橘子","蔬果","🍊",7,0.2,24,8,12,3,true,"补水并改善心情。"],
    ["pear","雪梨","蔬果","🍐",9,0.25,20,7,14,2,true,"多汁水果，能缓解口渴。"],
    ["dried-vegetables","脱水蔬菜","蔬果","🥬",18,0.1,730,0,0,0,false,"复水后加入米面，长期储存轻便。"],
    ["fruit-can","什锦水果罐头","蔬果","🍑",21,0.34,1095,16,18,5,true,"甜味水果罐头，改善单调饮食。"],
    ["freeze-dried-fruit","冻干草莓","蔬果","🍓",45,0.08,1095,9,0,6,true,"轻巧的高士气零食。"],
    ["mushroom-dry","干香菇","蔬果","🍄",29,0.1,1095,0,0,0,false,"泡发后提升炖菜营养。"],
    ["canned-peaches","黄桃罐头","蔬果","🍑",19,0.32,1095,14,17,5,true,"甜罐头，补水并恢复精神。"],
    ["cooking-oil","食用油","调味","🫙",16,0.5,540,0,0,0,false,"烹饪必需品，能提高餐食能量。"],
    ["salt","食盐","调味","🧂",4,0.5,1825,0,0,0,false,"烹饪和腌制需要的基础调味料。"],
    ["sugar","白砂糖","调味","🧂",9,0.5,1095,0,0,0,false,"可制作热饮，提供少量能量。"],
    ["soy-sauce","酱油","调味","🫙",12,0.5,730,0,0,0,false,"延长食物风味，烹饪材料。"],
    ["vinegar","米醋","调味","🫙",10,0.5,730,0,0,0,false,"烹饪调味，也可用于腌菜。"],
    ["chili-sauce","辣椒酱","调味","🌶️",17,0.3,730,2,0,4,true,"辛辣调味，略微振作精神。"],
    ["hotpot-base","火锅底料","调味","🍲",18,0.2,540,0,0,0,false,"配合蔬菜和肉类制作热锅。"],
    ["seasoning","复合调味料","调味","🧂",15,0.2,730,0,0,0,false,"让简易食材变成完整料理。"],
    ["chocolate","黑巧克力","零食","🍫",24,0.1,365,13,0,8,true,"高热量甜食，快速稳定情绪。"]
  ].forEach(function (x) { addFood.apply(null, x); });

  [
    ["bottled-water","瓶装水","💧",3,0.6,730,38,"标准饮用水，储备和外出都方便。"],
    ["large-water","桶装饮用水","🚰",18,5,540,90,"大容量饮水，单位价格低但占空间。"],
    ["water-purification","净水片","💊",28,0.04,1460,35,"处理一份原水，净化后可饮用。"],
    ["juice","橙汁","🧃",12,0.35,180,22,"补充水分和少量精神。"],
    ["milk","常温牛奶","🥛",9,0.25,180,18,"提供饮水和营养，开封后尽快饮用。"],
    ["tea","红茶茶包","🍵",22,0.08,730,14,"用热水冲泡，缓和精神压力。"],
    ["coffee","速溶咖啡","☕",35,0.1,730,10,"热饮提神，减少疲惫感。"],
    ["sports-drink","电解质饮料","🥤",13,0.5,180,34,"补充水分，探索后恢复更快。"],
    ["thermos-water","保温壶热水","🫖",15,0.8,2,46,"暖身热水，减少受寒影响。"],
    ["mineral-water","矿泉水","💧",5,0.55,730,42,"矿物质饮水，稳定补水。"],
    ["cocoa","可可粉","🥤",31,0.18,540,8,"与净水加热冲调，提升士气。"]
  ].forEach(function (x) { addDrink.apply(null, [x[0],x[1],x[2],x[3],x[4],x[5],x[6],x[7]]); });

  addResource("rain-filter","便携滤水器","饮水","🧰",138,.5,.4,"安装后每天净化备用水。",{kind:"install",upgrade:"purifier"},12);
  addResource("water-canteen","不锈钢水壶","工具","🫗",42,.35,.25,"探索时携带饮水，降低外出缺水风险。",{kind:"tool",skill:"survival",value:1},18);
  addResource("water-tank","储水箱","建材","🛢️",680,12,9,"安装后扩充安全屋储水容量。",{kind:"install",upgrade:"tank"},4);

  [
    ["bandage","无菌绷带","医疗","🩹",18,.08,.05,"处理轻伤，恢复 8 点生命。",{kind:"medicine",heal:8},80],
    ["disinfectant","碘伏消毒液","医疗","🧴",26,.2,.15,"清洁伤口，提高免疫状态。",{kind:"medicine",heal:3,immunity:12},30],
    ["fever-medicine","退烧药","医疗","💊",42,.03,.03,"缓解发热状态，恢复 12 点生命。",{kind:"medicine",heal:12,immunity:4},30],
    ["painkillers","止痛药","医疗","💊",37,.03,.03,"缓解疼痛，恢复 8 点生命和 4 点精神。",{kind:"medicine",heal:8,morale:4},30],
    ["cold-medicine","感冒药","医疗","💊",52,.04,.04,"治疗轻度感冒，提升免疫状态。",{kind:"medicine",heal:7,immunity:15},25],
    ["medical-kit","综合医疗包","医疗","🧰",210,.8,.7,"完整急救包，治疗中度伤势。",{kind:"medicine",heal:35,immunity:12},10],
    ["thermometer","电子体温计","医疗","🌡️",45,.1,.1,"用于监测体温；持有时更早发现低体温。",{kind:"tool",skill:"medical",value:1},15],
    ["first-aid","户外急救包","医疗","🩺",145,.5,.4,"探索途中急救，受伤后恢复生命。",{kind:"medicine",heal:25,immunity:8},15],
    ["antibiotic","广谱消炎片","医疗","💊",88,.03,.03,"游戏化治疗感染，降低免疫损耗。",{kind:"medicine",heal:10,immunity:24},14],
    ["vitamins","复合维生素","医疗","🟠",33,.04,.03,"补充营养，减少长期单一饮食的惩罚。",{kind:"medicine",immunity:17,morale:2},24],
    ["stomach-medicine","肠胃药","医疗","💊",29,.03,.03,"缓解食物不适，恢复 10 点生命。",{kind:"medicine",heal:10},24],
    ["medical-mask","医用口罩","医疗","😷",8,.02,.02,"探索污染地点时降低感染风险。",{kind:"tool",skill:"medical",value:.3},90]
  ].forEach(function (x) { addResource(x[0],x[1],x[2],x[3],x[4],x[5],x[6],x[7],x[8],x[9]); });

  [
    ["battery","AA 电池","能源","🔋",8,.05,.04,"低功率设备应急电源。",{kind:"fuel",fuel:1},80],
    ["powerbank","移动电源","能源","🔋",138,.35,.2,"给照明和通讯设备供电。",{kind:"fuel",fuel:12},18],
    ["diesel","柴油","能源","🛢️",12,1,.9,"发电机每日消耗一桶以维持供暖。",{kind:"fuel",fuel:1},45],
    ["coal","无烟煤","能源","⚫",9,1,.6,"备用燃料，需安装燃煤炉后使用。",{kind:"fuel",fuel:1},55],
    ["firewood","干燥木柴","能源","🪵",7,1,.8,"柴炉燃料，每日约消耗三捆。",{kind:"fuel",fuel:1},65],
    ["gas-canister","丁烷气罐","能源","🛢️",36,.45,.4,"便携炉具燃料，可供烹饪使用。",{kind:"fuel",fuel:3},30],
    ["solar-panel","折叠太阳能板","能源","☀️",980,9,8,"安装后白天产生少量电力，优先维持照明。",{kind:"install",upgrade:"solar"},5],
    ["generator","静音柴油发电机","能源","⚙️",4200,28,24,"安装后可用柴油维持供电和电暖器。",{kind:"install",upgrade:"generator"},3],
    ["battery-bank","储能电池组","能源","🔋",1250,14,10,"安装后储存电力，缓冲短时断电。",{kind:"install",upgrade:"battery"},4],
    ["wind-turbine","小型风力发电机","能源","🌬️",1680,18,14,"安装后在大风日补充安全屋电力。",{kind:"install",upgrade:"wind"},3],
    ["gasoline","汽油","能源","⛽",14,1,.85,"应急燃料，可为发电机补充能源。",{kind:"fuel",fuel:1},36],
    ["hand-crank","手摇发电器","能源","🔌",96,.8,.5,"短时间为手电和收音机充电。",{kind:"tool",skill:"engineering",value:1},12],
    ["solar-battery","太阳能蓄电池","能源","🔋",760,8,5,"配合太阳能板储存电量。",{kind:"install",upgrade:"battery"},5],
    ["biofuel","固体酒精块","能源","🔥",21,.3,.25,"小火炉和烹饪用应急燃料。",{kind:"fuel",fuel:2},30]
  ].forEach(function (x) { addResource(x[0],x[1],x[2],x[3],x[4],x[5],x[6],x[7],x[8],x[9]); });

  [
    ["down-coat","长款羽绒服","保暖","🧥",680,1.4,1.1,36,"高蓬松度外套，显著提升抗寒能力。",{kind:"wear",warmth:20,slot:"outer"}],
    ["thermal-underwear","保暖内衣","保暖","🩳",168,.45,.35,50,"贴身保暖，适合叠穿。",{kind:"wear",warmth:8,slot:"inner"}],
    ["snow-boots","雪地靴","保暖","🥾",320,1.1,.9,50,"防滑保温，降低外出冻伤风险。",{kind:"wear",warmth:7,slot:"feet"}],
    ["gloves","加厚手套","保暖","🧤",95,.2,.16,60,"减少寒冷天气的体力损失。",{kind:"wear",warmth:4,slot:"hands"}],
    ["scarf","羊毛围巾","保暖","🧣",78,.2,.2,60,"保护颈部，少量增加保暖值。",{kind:"wear",warmth:3,slot:"neck"}],
    ["wool-socks","羊毛袜","保暖","🧦",45,.12,.1,60,"改善脚部保温。",{kind:"wear",warmth:2,slot:"feet"}],
    ["insulated-pants","防风棉裤","保暖","👖",230,.8,.65,45,"厚实长裤，提升下肢保暖。",{kind:"wear",warmth:7,slot:"legs"}],
    ["wool-blanket","羊毛毯","保暖","🧶",138,1.4,1.1,9999,"休息时盖住可减少低温带来的损失。",{kind:"comfort",morale:2,warmth:2}],
    ["sleeping-bag","极地睡袋","保暖","🛌",460,2.1,1.4,9999,"夜间休息时增加保暖并恢复体力。",{kind:"comfort",morale:3,warmth:5}],
    ["hand-warmer","暖宝宝","保暖","♨️",6,.04,.03,1095,"一次性热源，探索时抵御低温。",{kind:"warmth",warmth:4}],
    ["electric-blanket","电热毯","保暖","🛏️",230,1.3,.9,9999,"通电后提升卧室温度，消耗少量电力。",{kind:"install",upgrade:"blanket"}],
    ["space-heater","电暖器","保暖","♨️",540,5,4,9999,"安装后提高室温，停电时需要发电机。",{kind:"install",upgrade:"heater"}],
    ["ski-goggles","雪镜","保暖","🥽",118,.18,.12,9999,"探索暴雪地区时提高视野和成功率。",{kind:"tool",skill:"exploration",value:1}],
    ["face-mask","防风面罩","保暖","🥷",88,.16,.12,60,"外出时降低暴露带来的体温损失。",{kind:"wear",warmth:3,slot:"face"}],
    ["insulated-suit","连体防寒服","保暖","🧥",1240,2.5,2,45,"高等级全身防寒装备。",{kind:"wear",warmth:24,slot:"outer"}]
  ].forEach(function (x) { addItem(x[0],x[1],x[2],x[3],x[4],x[5],x[5] * .8,x[6],x[7],x[8]); });

  [
    ["toilet-paper","卫生纸","日用","🧻",8,.3,.4,"清洁与生活必需品。",{kind:"hygiene",clean:3}],
    ["toothpaste","含氟牙膏","个人护理","🪥",16,.12,.1,"每日清洁用品，略微恢复清洁度。",{kind:"hygiene",clean:7}],
    ["toothbrush","软毛牙刷","个人护理","🪥",9,.05,.03,"搭配牙膏维持卫生。",{kind:"hygiene",clean:4}],
    ["soap","香皂","清洁","🧼",10,.12,.1,"清洁身体，降低感染风险。",{kind:"hygiene",clean:12}],
    ["detergent","浓缩洗衣液","清洁","🧴",24,.7,.55,"清洗衣物，恢复清洁度。",{kind:"hygiene",clean:15}],
    ["trash-bags","加厚垃圾袋","日用","🗑️",12,.25,.25,"储存垃圾，避免安全屋卫生下降。",{kind:"hygiene",clean:5}],
    ["wet-wipes","消毒湿巾","清洁","🧻",14,.2,.15,"断水时快速清洁，恢复清洁度。",{kind:"hygiene",clean:11}],
    ["cleaning-kit","家庭清洁套装","清洁","🧽",58,1.2,.8,"一次整理可显著提升安全屋卫生。",{kind:"hygiene",clean:26}],
    ["sanitary-pads","卫生巾","个人护理","🧻",22,.18,.16,"个人护理用品，维持生活舒适度。",{kind:"hygiene",clean:8}],
    ["disposable-gloves","一次性手套","个人护理","🧤",13,.08,.06,"处理伤口和污染物时保持清洁。",{kind:"hygiene",clean:6}],
    ["portable-toilet","便携马桶","日用","🚽",168,4,3,"断水后维持卫生的安全屋设施。",{kind:"install",upgrade:"toilet"}],
    ["waterproof-tarp","防水篷布","日用","🟦",82,1.8,1.5,"封堵窗缝或搭建临时防风层。",{kind:"material",tag:"fabric"}]
  ].forEach(function (x) { addResource(x[0],x[1],x[2],x[3],x[4],x[5],x[6],x[7],x[8]); });

  [
    ["timber","松木板材","建材","🪵",32,2.5,2.1,"房屋加固和货架制作材料。",{kind:"material",tag:"wood"},45],
    ["steel","角钢","建材","🔩",75,3,1.4,"安全门、炉架和结构加固材料。",{kind:"material",tag:"metal"},34],
    ["glass","双层玻璃","建材","🪟",130,5,3,"更换窗户后提高保温系数。",{kind:"material",tag:"glass"},12],
    ["screws","螺丝零件盒","建材","🔩",18,.35,.2,"维修和建造的通用紧固件。",{kind:"material",tag:"parts"},60],
    ["wire","铜芯电线","建材","〰️",28,.7,.5,"连接发电、储能和用电设备。",{kind:"material",tag:"parts"},50],
    ["duct-tape","防水胶带","建材","🩹",22,.25,.18,"密封门窗和管道缝隙。",{kind:"material",tag:"parts"},55],
    ["insulation-board","保温板","建材","🧊",62,1.8,1.4,"核心保温改造材料。",{kind:"material",tag:"insulation"},30],
    ["cement","快干水泥","建材","🧱",36,5,3,"固定门框和修补墙体。",{kind:"material",tag:"building"},40],
    ["tool-kit","家用工具箱","工具","🧰",180,3.2,2.3,"维修效率提高，可参与全部基础建造。",{kind:"tool",skill:"engineering",value:2},18],
    ["repair-parts","通用维修零件","工具","⚙️",56,.65,.42,"供电设备和家用电器的维修材料。",{kind:"material",tag:"parts"},40],
    ["multimeter","数字万用表","工具","📟",160,.5,.35,"排查电路故障，提高工程技能。",{kind:"tool",skill:"engineering",value:2},10],
    ["wrench","活动扳手","工具","🔧",42,.8,.35,"通用维修工具。",{kind:"tool",skill:"engineering",value:1},20],
    ["hammer","羊角锤","工具","🔨",36,.7,.32,"木作施工工具。",{kind:"tool",skill:"strength",value:1},20],
    ["hand-saw","折叠手锯","工具","🪚",64,.9,.45,"切割木板，制作货架。",{kind:"tool",skill:"engineering",value:1},16],
    ["nails","镀锌钉","建材","📌",14,.5,.3,"木工和门窗加固材料。",{kind:"material",tag:"parts"},60],
    ["thermal-curtain","加厚隔热窗帘","建材","🪟",210,2.4,1.8,"安装后减少夜间热量流失。",{kind:"install",upgrade:"curtain"},8],
    ["door-lock","防盗门锁","建材","🔐",156,1.2,.6,"提高安全屋防御能力。",{kind:"material",tag:"security"},14],
    ["water-filter","家用滤芯","建材","🧽",96,.4,.3,"净水设备替换滤芯。",{kind:"material",tag:"water"},16],
    ["flashlight","强光手电","工具","🔦",88,.4,.3,"探索黑暗地点时提高成功率。",{kind:"tool",skill:"exploration",value:1},20],
    ["radio","短波收音机","工具","📻",320,1.4,1,"接收城市广播，可提早获知事件。",{kind:"install",upgrade:"radio"},8],
    ["steel-door-kit","防盗门加固套件","建材","🚪",760,12,7,"大幅提高房屋防御。",{kind:"install",upgrade:"door"},3],
    ["ventilation-kit","手动通风组件","建材","🌀",520,6,4,"维持室内空气流通，降低疾病风险。",{kind:"install",upgrade:"ventilation"},5],
    ["waterproof-seal","门窗密封条","建材","🧵",48,.45,.32,"减少冷风渗入，辅助保温施工。",{kind:"material",tag:"insulation"},35]
  ].forEach(function (x) { addResource(x[0],x[1],x[2],x[3],x[4],x[5],x[6],x[7],x[8],x[9]); });

  [
    ["potato-seeds","土豆种薯","种植","🥔",26,.4,.35,"可在温室播种，约 7 天收获。",{kind:"seed",crop:"potato",days:7,yield:6},30],
    ["tomato-seeds","番茄种子","种植","🍅",19,.05,.03,"温室作物，约 9 天成熟。",{kind:"seed",crop:"tomato",days:9,yield:5},30],
    ["cabbage-seeds","白菜种子","种植","🥬",16,.05,.03,"耐寒叶菜，约 8 天成熟。",{kind:"seed",crop:"cabbage",days:8,yield:6},30],
    ["carrot-seeds","胡萝卜种子","种植","🥕",17,.05,.03,"根茎蔬菜，约 8 天成熟。",{kind:"seed",crop:"carrot",days:8,yield:5},30],
    ["corn-seeds","玉米种子","种植","🌽",18,.08,.05,"高能量作物，约 10 天成熟。",{kind:"seed",crop:"corn",days:10,yield:5},25],
    ["soy-seeds","黄豆种子","种植","🫘",22,.08,.05,"蛋白质作物，约 9 天成熟。",{kind:"seed",crop:"beans",days:9,yield:5},25],
    ["onion-seeds","洋葱种子","种植","🧅",15,.05,.03,"耐存放调味蔬菜，约 8 天成熟。",{kind:"seed",crop:"onion",days:8,yield:5},25],
    ["radish-seeds","萝卜种子","种植","🌱",12,.05,.03,"生长周期短，约 6 天成熟。",{kind:"seed",crop:"carrot",days:6,yield:4},25],
    ["potting-soil","营养培养土","种植","🟫",32,4,3,"温室播种需要的基础介质。",{kind:"material",tag:"garden"},30],
    ["fertilizer","缓释肥料","种植","🧪",58,2,1.3,"缩短作物生长期并增加产量。",{kind:"material",tag:"garden"},20],
    ["planter-box","深型种植箱","种植","🪴",148,5,4,"建成温室后可增加一个种植位。",{kind:"material",tag:"garden"},8],
    ["grow-light","全光谱补光灯","种植","💡",380,3.5,2.5,"温室必需设备，断电会降低生长速度。",{kind:"install",upgrade:"growlight"},6],
    ["irrigation","滴灌管线","种植","🚿",220,2.6,2,"减少温室每日耗水。",{kind:"install",upgrade:"irrigation"},5],
    ["compost","厨余堆肥","种植","♻️",22,2,.9,"加入种植箱可增加作物产量。",{kind:"material",tag:"garden"},30]
  ].forEach(function (x) { addResource(x[0],x[1],x[2],x[3],x[4],x[5],x[6],x[7],x[8],x[9]); });

  [
    ["novel","末日题材小说","娱乐","📖",42,.35,.24,0,"长篇小说能在独处时恢复精神。",{kind:"comfort",morale:14}],
    ["comics","漫画合订本","娱乐","📚",38,.4,.25,0,"轻松读物，快速缓解压力。",{kind:"comfort",morale:12}],
    ["handheld-game","掌机","娱乐","🎮",680,.55,.25,0,"电力充足时玩游戏可大幅恢复精神。",{kind:"comfort",morale:20}],
    ["music-player","便携音乐播放器","娱乐","🎧",420,.35,.2,0,"音乐帮助对抗长期隔离。",{kind:"comfort",morale:17}],
    ["chess","象棋套装","娱乐","♟️",36,.65,.4,0,"可与幸存者共同娱乐。",{kind:"comfort",morale:10}],
    ["playing-cards","扑克牌","娱乐","🃏",12,.12,.08,0,"轻便的多人娱乐用品。",{kind:"comfort",morale:8}],
    ["radio-drama","有声故事存储卡","娱乐","🎙️",85,.03,.01,0,"搭配收音机播放故事，恢复精神。",{kind:"comfort",morale:15}],
    ["photo-album","家庭相册","娱乐","🖼️",24,.45,.28,0,"回忆带来安定感，恢复精神。",{kind:"comfort",morale:12}],
    ["harmonica","口琴","娱乐","🎼",88,.18,.1,0,"练习或演奏可稳定情绪。",{kind:"comfort",morale:13}],
    ["board-game","合作桌游","娱乐","🎲",96,.9,.55,0,"邀请同伴一起游戏，增加关系。",{kind:"comfort",morale:16}],
    ["sketchbook","速写本","娱乐","✏️",25,.28,.2,0,"记录日常，缓解孤独。",{kind:"comfort",morale:9}],
    ["plant-pot","绿植盆栽","娱乐","🪴",58,1.2,1,0,"安全屋内的绿意提升舒适度。",{kind:"comfort",morale:11}]
  ].forEach(function (x) { addItem(x[0],x[1],x[2],x[3],x[4],x[5],x[6],9999,x[7],x[8]); });

  [
    ["fridge","节能冰箱","电器","🧊",2400,38,45,"安装后延缓食物腐坏，消耗电力。",{kind:"install",upgrade:"fridge"},3],
    ["freezer","立式冷柜","电器","❄️",3300,52,60,"大幅延长冷冻食材的保质期。",{kind:"install",upgrade:"freezer"},2],
    ["induction-cooker","电磁炉","电器","🍳",540,3.4,2.7,"可用于烹饪，每次耗少量能源。",{kind:"install",upgrade:"cooker"},8],
    ["led-lamp","应急 LED 灯","电器","💡",96,.7,.55,"停电后为室内提供照明。",{kind:"install",upgrade:"lamp"},15],
    ["air-vent","循环换气扇","电器","🌀",320,2.4,1.8,"减少室内空气污浊。",{kind:"install",upgrade:"ventilation"},6],
    ["security-camera","无线监控套装","电器","📹",780,3.2,2.4,"提升防御并提前发现夜间动静。",{kind:"install",upgrade:"camera"},5],
    ["water-purifier","桌面净水器","电器","🚰",890,6.5,5,"每日生成少量饮用水，需更换滤芯。",{kind:"install",upgrade:"purifier"},4],
    ["electric-kettle","电热水壶","电器","🫖",128,1.1,.8,"有电时烧水，便于制作热饮。",{kind:"install",upgrade:"kettle"},10],
    ["freezer-box","保温冷藏箱","电器","🧊",260,3.8,3.5,"无需电力的短期冷藏箱。",{kind:"install",upgrade:"coolbox"},8],
    ["solar-lantern","太阳能营地灯","电器","🏮",160,.75,.45,"日照后充电，夜间提供低耗照明。",{kind:"install",upgrade:"lantern"},8],
    ["indoor-thermometer","室内温湿度计","电器","🌡️",68,.2,.12,"准确查看室温和湿度变化。",{kind:"tool",skill:"engineering",value:1},14],
    ["water-pump","手动抽水泵","电器","🚿",420,8,6,"从备用水箱取水，无需市电。",{kind:"install",upgrade:"pump"},4]
  ].forEach(function (x) { addResource(x[0],x[1],x[2],x[3],x[4],x[5],x[6],x[7],x[8],x[9]); });

  var origins = [
    { id:"office", name:"普通上班族", icon:"💼", cash:78000, skill:"trade", level:2, text:"收入稳定，起步均衡。", home:"城南公寓", insulation:58, storage:950 },
    { id:"wealthy", name:"富二代", icon:"💎", cash:190000, skill:"social", level:2, text:"资金充足，技能需要后天补齐。", home:"湖畔别墅", insulation:66, storage:1700 },
    { id:"soldier", name:"退伍军人", icon:"🪖", cash:52000, skill:"strength", level:4, text:"体能和防御强，采购资金较少。", home:"旧城住宅", insulation:55, storage:900 },
    { id:"doctor", name:"医生", icon:"🩺", cash:68000, skill:"medical", level:4, text:"医疗经验丰富，拥有社区诊疗渠道。", home:"医院职工房", insulation:60, storage:1000 },
    { id:"engineer", name:"工程师", icon:"🛠️", cash:72000, skill:"engineering", level:4, text:"维修与建造更有效率。", home:"工业园公寓", insulation:62, storage:1050 },
    { id:"farmer", name:"农业从业者", icon:"🌱", cash:48000, skill:"farming", level:4, text:"懂得种植和食物保存。", home:"近郊平房", insulation:50, storage:1300 },
    { id:"merchant", name:"商人", icon:"🤝", cash:95000, skill:"trade", level:4, text:"采购议价，擅长做生意。", home:"中央公寓", insulation:60, storage:1050 },
    { id:"outdoors", name:"户外爱好者", icon:"🏕️", cash:56000, skill:"exploration", level:4, text:"耐寒、探索能力出众。", home:"山脚小屋", insulation:64, storage:850 },
    { id:"student", name:"大学生", icon:"🎒", cash:36000, skill:"learning", level:4, text:"学习速度快，资金有限。", home:"学校宿舍", insulation:48, storage:700 }
  ];

  var talents = [
    ["囤货大师","仓储容量增加 350 kg。"],
    ["精打细算","市场采购价格降低 8%。"],
    ["耐寒体质","穿衣保暖额外增加 8 点。"],
    ["生存专家","探索收获和成功率小幅提升。"],
    ["心灵手巧","建造与制作耗时减少 20%。"],
    ["天生商人","出售物资获得更多资金。"],
    ["重生记忆","提前获知天气与市场变化。"],
    ["幸运儿","探索时更容易找到稀有补给。"],
    ["钢铁意志","精神状态下降速度降低。"],
    ["轻度睡眠","每天额外恢复 12 点体力。"],
    ["空间异能","安全屋仓储容量增加 600 kg。"],
    ["医生直觉","药品治疗效果提高 15%。"],
    ["工匠世家","建造材料消耗降低 10%。"],
    ["餐桌不浪费","饱食度低时才自动消耗储备餐食。"],
    ["社区熟面孔","与邻里建立关系更快。"],
    ["稳健投资","每日工作收入提高 8%。"],
    ["野外耳目","探索遭遇危险的概率下降。"],
    ["园艺高手","作物产量提高 25%。"],
    ["耐力惊人","行动体力消耗降低 15%。"],
    ["冷静判断","随机事件的负面后果降低。"]
  ];

  var difficulties = [
    { id:"casual", name:"休闲", cash:1.35, need:.78, risk:.72, desc:"资源消耗放缓，适合体验系统。" },
    { id:"normal", name:"普通", cash:1, need:1, risk:1, desc:"资源与事件保持标准节奏。" },
    { id:"hard", name:"困难", cash:.75, need:1.18, risk:1.25, desc:"物资更贵，天气和探索更危险。" },
    { id:"hardcore", name:"硬核", cash:.52, need:1.42, risk:1.55, desc:"伤病严峻，死亡后本周目结束。" }
  ];

  var nav = [
    ["overview","⌂","指挥台"], ["market","▦","采购市场"], ["warehouse","▤","物资仓库"],
    ["shelter","⌂","安全屋"], ["explore","⌖","城市探索"], ["people","♧","幸存者"],
    ["journal","▧","日志与目标"], ["settings","⚙","设置与存档"]
  ];

  var upgrades = [
    {id:"insulation",name:"墙体保温层",icon:"🧊",cost:6200,hours:4,materials:{ "insulation-board":8,"duct-tape":2 },desc:"保温系数 +16，室温散失减缓。",apply:function(s){s.house.insulation=Math.min(95,s.house.insulation+16);}},
    {id:"seal",name:"门窗密封",icon:"🪟",cost:2800,hours:2,materials:{ "waterproof-seal":4,"screws":2 },desc:"保温系数 +8，减少冷风渗漏。",apply:function(s){s.house.insulation=Math.min(95,s.house.insulation+8);}},
    {id:"door",name:"安全防盗门",icon:"🚪",cost:5800,hours:4,materials:{ "steel":5,"door-lock":1,"screws":3 },desc:"防御等级 +24，降低夜间入侵损失。",apply:function(s){s.house.defense+=24;}},
    {id:"shelves",name:"仓储货架",icon:"📦",cost:2200,hours:2,materials:{ "timber":8,"screws":2 },desc:"增加 650 kg 和 4 m³ 仓储空间。",apply:function(s){s.house.storage+=650;s.house.volume+=4;}},
    {id:"woodstove",name:"柴火取暖炉",icon:"🔥",cost:4200,hours:3,materials:{ "steel":3,"repair-parts":2,"timber":4 },desc:"断电后燃烧木柴维持供暖。",apply:function(s){s.house.woodStove=true;}},
    {id:"generator",name:"静音发电机",icon:"⚙️",cost:7200,hours:5,materials:{ "wire":4,"repair-parts":4 },desc:"安装后消耗柴油维持电暖器和冷柜。",apply:function(s){s.house.generator=true;}},
    {id:"tank",name:"储水与净化",icon:"🚰",cost:4600,hours:3,materials:{ "water-tank":1,"water-filter":2,"wire":1 },desc:"增加 240 L 净水储备，免疫短时断水。",apply:function(s){s.house.waterCapacity+=240;s.house.waterStored+=80;}},
    {id:"greenhouse",name:"室内温室",icon:"🌱",cost:8200,hours:6,materials:{ "planter-box":2,"potting-soil":6,"grow-light":1 },desc:"解锁两个种植位，作物在安全屋内生长。",apply:function(s){s.house.greenhouse=true;s.house.growLight=true;}},
    {id:"fridge",name:"冷藏与保鲜",icon:"🧊",cost:5400,hours:3,materials:{ "repair-parts":2 },desc:"延缓新鲜食物腐坏；停电后保鲜能力降低。",apply:function(s){s.house.fridge=true;}},
    {id:"purifier",name:"净水设备",icon:"💧",cost:3800,hours:3,materials:{ "water-filter":2,"wire":2 },desc:"每天净化 8 L 水，设备运行需要供电。",apply:function(s){s.house.purifier=true;}},
    {id:"camera",name:"监控与广播",icon:"📹",cost:3900,hours:3,materials:{ "wire":3,"repair-parts":2 },desc:"防御 +10，并提前发现风险事件。",apply:function(s){s.house.defense+=10;s.house.camera=true;}},
    {id:"curtain",name:"隔热窗帘",icon:"🪟",cost:1800,hours:2,materials:{ "thermal-curtain":1 },desc:"夜间保温 +5，降低寒夜损耗。",apply:function(s){s.house.nightHeat=5;}},
    {id:"battery",name:"储能系统",icon:"🔋",cost:4500,hours:4,materials:{ "battery-bank":1,"wire":3 },desc:"储存太阳能并支撑低功率设备。",apply:function(s){s.house.battery=true;s.house.batteryCharge=16;}},
    {id:"solar",name:"太阳能供电",icon:"☀️",cost:8800,hours:5,materials:{ "solar-panel":1,"wire":4 },desc:"晴天产生电力，维持照明、净水和通风。",apply:function(s){s.house.solar=true;}},
    {id:"ventilation",name:"通风净化",icon:"🌬️",cost:2500,hours:2,materials:{ "ventilation-kit":1,"wire":1 },desc:"卫生与免疫下降放缓。",apply:function(s){s.house.ventilation=true;}},
    {id:"workbench",name:"维修工作台",icon:"🛠️",cost:1900,hours:2,materials:{ "timber":5,"steel":2 },desc:"工程类制作效率提高，修理工具更耐用。",apply:function(s){s.house.workbench=true;}}
  ];

  var recipes = [
    {id:"rice-bowl",name:"热米饭",icon:"🍚",hours:.5,need:{"rice":1,"bottled-water":1},desc:"最简单的热食，恢复饱食和少量精神。",result:"meal-rice"},
    {id:"noodle-soup",name:"暖心汤面",icon:"🍜",hours:1,need:{"noodles":1,"bottled-water":1,"cabbage":1},desc:"热汤面补水并恢复体力。",result:"meal-noodles"},
    {id:"stew",name:"肉菜炖锅",icon:"🍲",hours:2,need:{"pork":1,"potato":1,"carrot":1,"bottled-water":1},desc:"营养均衡，适合末日后的正餐。",result:"meal-stew"},
    {id:"porridge",name:"小米粥",icon:"🥣",hours:1,need:{"millet":1,"bottled-water":1},desc:"容易消化，缺水时也能补充水分。",result:"meal-porridge"},
    {id:"bean-rice",name:"豆饭",icon:"🫘",hours:1.5,need:{"rice":1,"beans":1,"bottled-water":1},desc:"耐饿的植物蛋白主食。",result:"meal-beans"},
    {id:"fish-soup",name:"鱼肉浓汤",icon:"🐟",hours:1.5,need:{"fish":1,"mushroom":1,"bottled-water":1},desc:"优质蛋白与蔬菜汤。",result:"meal-fish"},
    {id:"hotpot",name:"末日火锅",icon:"🍲",hours:2.5,need:{"hotpot-base":1,"cabbage":1,"luncheon-meat":1,"bottled-water":1},desc:"消耗多一些储备，但显著恢复精神。",result:"meal-hotpot"},
    {id:"flatbread",name:"应急面饼",icon:"🫓",hours:1,need:{"flour":1,"cooking-oil":1},desc:"无需冷藏的高能量熟食。",result:"meal-flatbread"}
  ];

  [
    ["meal-rice","热米饭","主食","🍚",10,.32,3,38,4,2,true,"煮熟的米饭，便宜可靠。"],
    ["meal-noodles","暖心汤面","主食","🍜",14,.38,2,32,16,3,true,"一碗热汤面，恢复饱食和水分。"],
    ["meal-stew","肉菜炖锅","主食","🍲",32,.56,2,52,14,5,true,"肉、根茎和热汤组成的完整正餐。"],
    ["meal-porridge","小米粥","主食","🥣",11,.35,2,26,16,2,true,"温热软粥，缓解寒冷和饥饿。"],
    ["meal-beans","豆饭","主食","🫘",18,.42,2,42,7,1,true,"植物蛋白丰富，耐饿。"],
    ["meal-fish","鱼肉浓汤","主食","🐟",28,.48,2,43,15,4,true,"热汤补水，提供高质量营养。"],
    ["meal-hotpot","末日火锅","主食","🍲",48,.62,1,48,18,13,true,"共享一锅热食，大幅恢复士气。"],
    ["meal-flatbread","应急面饼","主食","🫓",16,.26,120,31,0,1,true,"熟制干粮，适合留作路餐。"]
  ].forEach(function (x) { addFood.apply(null, x); });

  var locations = [
    {id:"supermarket",name:"城北超市",icon:"🏬",risk:12,hours:2,desc:"货架逐渐被清空，仍可能找到食物。",loot:["instant-noodles","bottled-water","toilet-paper","apple","bandage"]},
    {id:"warehouse",name:"物流仓库",icon:"📦",risk:25,hours:4,desc:"库存多，入口被积雪堵住。",loot:["rice","diesel","firewood","insulation-board","repair-parts"]},
    {id:"pharmacy",name:"社区药房",icon:"✚",risk:18,hours:2.5,desc:"药品稀缺，店门有撬动痕迹。",loot:["bandage","cold-medicine","vitamins","disinfectant","medical-kit"]},
    {id:"hardware",name:"五金街",icon:"🔩",risk:20,hours:3,desc:"建筑材料丰富，夜间很安静。",loot:["screws","wire","duct-tape","waterproof-seal","wrench"]},
    {id:"farm",name:"清河农庄",icon:"🌾",risk:28,hours:5,desc:"越过结冰河面，寻找种子和粮食。",loot:["potato","beans","potato-seeds","potting-soil","eggs"]},
    {id:"clinic",name:"安宁诊所",icon:"🩺",risk:24,hours:3,desc:"可能找到急救用品，也可能遇到伤者。",loot:["first-aid","fever-medicine","antibiotic","thermometer"]},
    {id:"station",name:"公交总站",icon:"🚌",risk:32,hours:4,desc:"交通已经停摆，遗留车辆里有补给。",loot:["sports-drink","flashlight","powerbank","emergency-ration"]},
    {id:"school",name:"第七中学",icon:"🏫",risk:18,hours:3,desc:"桌椅可拆解为木材，储物室值得搜查。",loot:["timber","novel","water-bottle","playing-cards"]},
    {id:"library",name:"市立图书馆",icon:"📚",risk:14,hours:3,desc:"安静但寒冷，能找到书籍和地图。",loot:["novel","comics","sketchbook","radio-drama"]},
    {id:"mall",name:"中央商场",icon:"🏙️",risk:36,hours:5,desc:"贵重物资多，竞争者也更多。",loot:["down-coat","medical-kit","canned-peaches","led-lamp"]},
    {id:"subway",name:"地铁检修站",icon:"🚇",risk:38,hours:5,desc:"黑暗、湿冷，可能找到电工用品。",loot:["battery","wire","multimeter","hand-crank"]},
    {id:"gas",name:"东郊加油站",icon:"⛽",risk:42,hours:6,desc:"燃料珍贵，夜间有未知动静。",loot:["diesel","gasoline","gas-canister","firewood"]},
    {id:"church",name:"山脚避难点",icon:"⛪",risk:17,hours:4,desc:"附近有人建立临时营地，可以交换物资。",loot:["blanket","water-purification","meal-rice","tea"]},
    {id:"apartment",name:"旧居民楼",icon:"🏚️",risk:30,hours:4,desc:"挨家搜寻有风险，但能发现生活用品。",loot:["trash-bags","soap","rice","waterproof-tarp"]},
    {id:"observatory",name:"北岭气象站",icon:"🔭",risk:26,hours:6,desc:"远郊气象站或许留下预警与电源。",loot:["solar-lantern","battery-bank","radio","thermal-underwear"]}
  ];

  var npcs = [
    {id:"lin",name:"林曼",role:"社区医生",icon:"👩🏻‍⚕️",skill:"medical",text:"愿意用医疗服务换取药品。"},
    {id:"zhou",name:"周师傅",role:"电工",icon:"👨🏻‍🔧",skill:"engineering",text:"能维修发电机和线路。"},
    {id:"chen",name:"陈野",role:"退伍搜救员",icon:"🧑🏻‍🚒",skill:"exploration",text:"熟悉旧城路线，善于搜救。"},
    {id:"mei",name:"梅姨",role:"粮食店老板",icon:"👩🏻‍🌾",skill:"trade",text:"知道哪里还能换到米面。"},
    {id:"yu",name:"余禾",role:"植物研究生",icon:"👩🏻‍🔬",skill:"farming",text:"可以帮你照料室内作物。"},
    {id:"an",name:"安宁",role:"独立摄影师",icon:"📷",skill:"social",text:"愿意分享城区消息和旧物。"}
  ];

  var eventPool = [
    {title:"寒潮预警提前",icon:"📡",text:"气象台将寒潮抵达时间提前了半天。街上开始出现抢购物资的人群。",a:"相信预警，立刻补充燃料",b:"趁物价还没涨去工作",effect:"fuel"},
    {title:"社区团购求助",icon:"🤝",text:"邻居敲门，希望用两箱罐头换一套保暖衣物。",a:"接受交换，建立信任",b:"婉拒并留下联系方式",effect:"trade"},
    {title:"旧市场甩卖",icon:"🏷️",text:"一家仓储店清仓处理剩余物资，库存有限。",a:"花费 ¥1,200 抢购补给",b:"保留现金",effect:"sale"},
    {title:"停电演练",icon:"⚡",text:"城区供电公司发布限电通知，今晚会进行分区测试。",a:"检查备用灯和燃料",b:"不作额外准备",effect:"power"},
    {title:"风雪中的求救",icon:"🆘",text:"窗外传来呼救声，一个老人被困在楼道里。",a:"花 1 小时送去热水",b:"留在屋内保持安全",effect:"rescue"},
    {title:"物流延迟",icon:"🚚",text:"快递站积压严重，部分订单可能多等一天。",a:"支付 ¥300 加急",b:"等待普通配送",effect:"shipping"},
    {title:"楼道漏风",icon:"🌬️",text:"大风从门缝灌进屋内，温度计下降得很快。",a:"消耗胶带马上封堵",b:"忍到明天再修",effect:"repair"},
    {title:"稀有种子到货",icon:"🌱",text:"农贸市场到了一批耐寒蔬菜种子，消息很快传开。",a:"买下种子，花费 ¥680",b:"暂不投资种植",effect:"seed"},
    {title:"夜间脚步声",icon:"👣",text:"深夜有人在楼道徘徊，试探每一扇门的锁。",a:"开启灯光并加固门锁",b:"保持安静，不暴露位置",effect:"defense"},
    {title:"广播里的坐标",icon:"📻",text:"短波广播提到北岭气象站仍有电力和燃料。",a:"标记地点，士气 +8",b:"记录后继续休息",effect:"hope"},
    {title:"食品价格跳涨",icon:"📈",text:"供应商宣布主粮价格上涨，超市限购进一步收紧。",a:"接受高价补货",b:"动用已有储备",effect:"prices"},
    {title:"邻里互助站",icon:"🏠",text:"社区志愿者正在收集毛毯和热水，帮助行动不便的人。",a:"捐出一条毛毯",b:"先保留物资",effect:"community"}
  ];

  var eventSites=["南城地铁站","旧港物流园","北坡公寓","中心商场","清河农贸市场","山路观测站","社区卫生院","城西加油站","河岸住宅区","老工业区"];
  var eventVariants=[
    {title:"路口冻裂",icon:"🌬️",effect:"repair",text:"寒风从{site}方向刮来，沿街几栋房屋的门窗开始漏风。",a:"用密封材料立即修补",b:"关紧内门减少热损"},
    {title:"货运道路封闭",icon:"🚚",effect:"shipping",text:"{site}附近的路面被冰层封住，几辆配送车暂时无法通行。",a:"支付费用协调绕行",b:"等待道路清理"},
    {title:"市场开始限购",icon:"📈",effect:"prices",text:"{site}一带的商家调整了限购规则，主粮和饮水的售价继续上涨。",a:"趁库存尚在补足大米",b:"优先消耗现有储备"},
    {title:"邻居敲门",icon:"🤝",effect:"trade",text:"来自{site}的邻居敲门，提出用生活用品换取一部分食物。",a:"交换一部分补给",b:"礼貌拒绝并留意消息"},
    {title:"分区停电通知",icon:"⚡",effect:"power",text:"电力部门通知{site}周边将进行分区限电，持续时间尚不确定。",a:"检查电池和照明",b:"留在屋内节约用电"},
    {title:"雪夜求援",icon:"🆘",effect:"rescue",text:"从{site}方向传来断断续续的求救声，像是有人被困在风雪里。",a:"带一瓶水前去查看",b:"先确保安全屋有人值守"},
    {title:"耐寒种子",icon:"🌱",effect:"seed",text:"{site}附近有人分享耐寒作物种子，库存只有少量。",a:"花钱带回种子和土",b:"暂不开始种植"},
    {title:"楼道异响",icon:"👣",effect:"defense",text:"深夜有人在{site}附近试探住户门锁，楼道的灯忽明忽暗。",a:"加固防盗门",b:"关闭灯光保持隐蔽"},
    {title:"气象站广播",icon:"📻",effect:"hope",text:"来自{site}的短波广播提到风暴会短暂停歇，外出窗口可能出现。",a:"记录坐标与时间",b:"收听后继续休息"},
    {title:"社区热水点",icon:"🏠",effect:"community",text:"{site}出现了临时互助点，志愿者正在收集保暖物资。",a:"捐出一份保暖用品",b:"先保留现有物资"}
  ];
  eventVariants.forEach(function(template,typeIndex){
    eventSites.forEach(function(site,siteIndex){
      eventPool.push({
        title:template.title+" · "+site,
        icon:template.icon,
        text:template.text.replace("{site}",site),
        a:template.a,
        b:template.b,
        effect:template.effect,
        id:"district-event-"+typeIndex+"-"+siteIndex
      });
    });
  });

  var achievements = [
    {id:"first-buy",name:"第一箱补给",desc:"完成第一笔采购。",check:function(s){return s.counters.buys>0;}},
    {id:"home-upgrade",name:"家园改造",desc:"完成一项安全屋升级。",check:function(s){return s.house.upgrades.length>0;}},
    {id:"ten-upgrades",name:"钢铁堡垒",desc:"完成十项安全屋升级。",check:function(s){return s.house.upgrades.length>=10;}},
    {id:"first-explore",name:"冰城足迹",desc:"成功完成一次外出探索。",check:function(s){return s.counters.explores>0;}},
    {id:"harvest",name:"末日菜园",desc:"收获一批自己种植的作物。",check:function(s){return s.counters.harvests>0;}},
    {id:"storm",name:"熬过寒潮",desc:"度过末日后的第七天。",check:function(s){return s.survivalDay>=7;}},
    {id:"hundred",name:"百日之后",desc:"末日后生存满一百天。",check:function(s){return s.survivalDay>=100;}}
  ];
  [
    ["first-shift","第一份工资","完成第一天工作。",function(s){return s.counters.workDays>=1;}],
    ["five-shifts","加班能手","累计工作五天。",function(s){return s.counters.workDays>=5;}],
    ["market-regular","常客","完成十笔采购。",function(s){return s.counters.buys>=10;}],
    ["stockpiler","成箱补给","累计买入一百件物资。",function(s){return s.counters.unitsBought>=100;}],
    ["cash-reserve","现金缓冲","持有十万元现金。",function(s){return s.money>=100000;}],
    ["skilled-builder","熟练工","工程技能达到 5 级。",function(s){return s.skills.engineering>=5;}],
    ["explorer-five","城区熟面孔","探索五个不同地点。",function(s){return new Set(s.journal.filter(function(x){return x.type==="location";}).map(function(x){return x.id;})).size>=5;}],
    ["explorer-ten","风雪老手","完成十次探索。",function(s){return s.counters.explores>=10;}],
    ["home-five","有备无患","完成五项安全屋改造。",function(s){return s.house.upgrades.length>=5;}],
    ["home-defense","固若金汤","安全屋防御达到 60。",function(s){return s.house.defense>=60;}],
    ["warm-room","温暖角落","末日后室温保持在 18°C 以上。",function(s){return s.phase==="survival"&&s.house.temperature>=18;}],
    ["clean-home","整洁生活","清洁度保持在 90 以上。",function(s){return s.player.cleanliness>=90;}],
    ["good-health","状态稳定","健康、免疫与精神均高于 80。",function(s){return s.player.health>=80&&s.player.immunity>=80&&s.player.sanity>=80;}],
    ["community-three","邻里守望","至少三位幸存者建立联系。",function(s){return Object.keys(s.contacts).length>=3;}],
    ["small-team","有人同行","邀请一位幸存者加入团队。",function(s){return Object.keys(s.contacts).some(function(id){return s.contacts[id].joined;});}],
    ["first-meal","一顿热饭","完成第一次烹饪。",function(s){return s.counters.crafts>=1;}],
    ["chef","安全屋主厨","累计完成十次烹饪。",function(s){return s.counters.crafts>=10;}],
    ["first-harvest","第一茬菜","收获一次安全屋作物。",function(s){return s.counters.harvests>=1;}],
    ["self-sufficient","菜园有成","累计收获五次作物。",function(s){return s.counters.harvests>=5;}],
    ["eventful","应对变化","处理五起世界事件。",function(s){return s.counters.events>=5;}],
    ["good-memory","记忆可靠","选择重生记忆天赋。",function(s){return s.talents.indexOf("重生记忆")>=0;}],
    ["cold-proof","穿暖再出发","装备保暖值达到 25。",function(s){return Object.keys(s.equipped||{}).reduce(function(a,k){return a+(getItem(s.equipped[k]).effect.warmth||0);},hasTalent("耐寒体质")?8:0)>=25;}],
    ["long-haul","坚守三十日","末日后生存满三十天。",function(s){return s.survivalDay>=30;}],
    ["halfway","走过半程","末日后生存满五十天。",function(s){return s.survivalDay>=50;}]
  ].forEach(function(x){achievements.push({id:x[0],name:x[1],desc:x[2],check:x[3]});});

  var state = loadState();
  var activePage = state ? "overview" : "start";

  function escapeHtml(value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, function (ch) {
      return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[ch];
    });
  }
  function money(value) { return "¥" + Math.round(value || 0).toLocaleString("zh-CN"); }
  function clamp(value, min, max) { return Math.max(min, Math.min(max, value)); }
  function sum(values) { return values.reduce(function (a,b) { return a + b; }, 0); }
  function getItem(id) { return itemById[id] || {id:id,name:id,icon:"📦",weight:1,volume:1,price:0,effect:{}}; }
  function addLog(title, text, icon) {
    if (!state) return;
    state.log.unshift({day:state.day, title:title, text:text, icon:icon || "•"});
    state.log = state.log.slice(0,80);
  }
  function hasTalent(name) { return !!state && state.talents.indexOf(name) >= 0; }
  function difficulty() { return difficulties.find(function(x){return state && x.id===state.difficulty;}) || difficulties[1]; }
  function currentOrigin() { return origins.find(function(x){return state && x.id===state.origin;}) || origins[0]; }
  function currentShop() { return shops.find(function(x){return x.id===shopId;}) || shops[0]; }
  function hasUpgrade(id) { return !!state && state.house.upgrades.indexOf(id) >= 0; }
  function ownsItem(id) { return inventoryCount("warehouse",id)+inventoryCount("pack",id)>0; }
  function inventoryCount(place,id) { return state && state.inventory && state.inventory[place] ? (state.inventory[place][id] || 0) : 0; }
  function inventoryTotal(id) { return inventoryCount("warehouse",id)+inventoryCount("pack",id); }
  function setCount(place,id,value) {
    if (!state.inventory[place]) state.inventory[place] = {};
    if (value <= 0) delete state.inventory[place][id];
    else state.inventory[place][id] = Math.floor(value);
  }
  function addInventory(place,id,count) {
    if (!count || count <= 0) return;
    setCount(place,id,inventoryCount(place,id)+count);
    if (!state.age[id]) state.age[id] = 0;
  }
  function removeInventory(place,id,count) {
    var current = inventoryCount(place,id);
    if (current < count) return false;
    setCount(place,id,current-count);
    return true;
  }
  function removeAny(id,count) {
    var fromWarehouse = Math.min(inventoryCount("warehouse",id),count);
    if (fromWarehouse) removeInventory("warehouse",id,fromWarehouse);
    var remaining = count-fromWarehouse;
    if (remaining) return removeInventory("pack",id,remaining);
    return true;
  }
  function inventoryLoad(place) {
    var data = state.inventory[place] || {};
    return Object.keys(data).reduce(function(acc,id){
      var item=getItem(id), qty=data[id];
      acc.weight += item.weight*qty; acc.volume += item.volume*qty; acc.count += qty; return acc;
    },{weight:0,volume:0,count:0});
  }
  function storageCapacity() {
    var kg=state.house.storage+(hasTalent("囤货大师")?350:0)+(hasTalent("空间异能")?600:0);
    return {weight:kg,volume:state.house.volume+(hasTalent("空间异能")?4:0)};
  }
  function storageCanHold(id,count,place) {
    var item=getItem(id);
    var load=inventoryLoad(place || "warehouse"), cap=place==="pack"?{weight:state.packLimit,volume:state.packVolume}:storageCapacity();
    var pending=place==="warehouse"?state.orders.reduce(function(a,o){return a+o.weight;},0):0;
    return load.weight+item.weight*count+pending<=cap.weight && load.volume+item.volume*count<=cap.volume;
  }
  function hasMaterials(materials) {
    return Object.keys(materials || {}).every(function(id){return inventoryTotal(id)>=(materials[id]||0);});
  }
  function materialLabel(materials) {
    return Object.keys(materials || {}).map(function(id){return getItem(id).name+" ×"+materials[id];}).join("、");
  }

  function storageBridge() {
    try {
      if (!window.StarlitApp && window.StarlitApp_getToolNames) {
        var names=JSON.parse(window.StarlitApp_getToolNames());
        window.StarlitApp={};
        names.forEach(function(name){if(window["StarlitApp_"+name]) window.StarlitApp[name]=window["StarlitApp_"+name];});
      } else if (window.StarlitApp && typeof window.StarlitApp.getToolNames==="function") {
        var toolNames=JSON.parse(window.StarlitApp.getToolNames());
        toolNames.forEach(function(name){if(window["StarlitApp_"+name]) window.StarlitApp[name]=window["StarlitApp_"+name];});
      }
      return window.StarlitApp && window.StarlitApp.storage ? window.StarlitApp.storage : null;
    } catch (error) { return null; }
  }
  function loadState() {
    var bridge=storageBridge();
    if (bridge && typeof bridge.storageGet==="function") {
      try {
        var envelope=JSON.parse(bridge.storageGet(STORAGE_KEY));
        if (envelope.success && envelope.data && envelope.data.exists) {
          var saved=JSON.parse(envelope.data.valueJson);
          if (saved && saved.version===1) { saveBackend="StarlitApp.storage"; return saved; }
        }
      } catch (error) { /* Browser fallback also supports local preview. */ }
    }
    try {
      var local=localStorage.getItem(STORAGE_KEY);
      if (local) {
        var browserState=JSON.parse(local);
        if (browserState && browserState.version===1) return browserState;
      }
    } catch (error) { /* Storage may be unavailable in private browsing. */ }
    return null;
  }
  function saveState() {
    if (!state) return;
    var payload=JSON.stringify(state);
    var savedInStarlit=false;
    try {
      var bridge=storageBridge();
      if (bridge && typeof bridge.storageSet==="function") {
        var result=JSON.parse(bridge.storageSet(STORAGE_KEY,payload));
        if (!result.success) throw new Error(result.error || "宿主存档不可用");
        saveBackend="StarlitApp.storage";
        savedInStarlit=true;
      }
    } catch (error) {
      savedInStarlit=false;
    }
    if (!savedInStarlit) {
      try { localStorage.setItem(STORAGE_KEY,payload); saveBackend="browser"; }
      catch (error) { saveBackend="memory"; }
    }
  }
  function deleteSavedState() {
    try { var bridge=storageBridge(); if(bridge&&bridge.storageRemove) bridge.storageRemove(STORAGE_KEY); } catch (error) {}
    try { localStorage.removeItem(STORAGE_KEY); } catch (error) {}
  }
  function parseSaveFile(text) {
    var data=JSON.parse(text);
    if (!data || data.version!==1 || !data.player || !data.inventory || !data.house) throw new Error("这不是有效的 Starlit 极寒末世存档。");
    return data;
  }

  function hashText(text) {
    var value=0;
    for(var i=0;i<text.length;i++) value=(value*31+text.charCodeAt(i))>>>0;
    return value;
  }
  function initStock() {
    var stock={};
    shops.forEach(function(shop){
      stock[shop.id]={};
      items.forEach(function(item){
        if(item.shops.indexOf(shop.id)<0) return;
        var seed=hashText(shop.id+item.id);
        var multiplier=shop.id==="wholesale"?1.6:shop.id==="secondhand"?.55:1;
        stock[shop.id][item.id]=Math.max(1,Math.round((item.stock*(.55+(seed%80)/100))*multiplier));
      });
    });
    return stock;
  }
  function marketMultiplier() {
    if(state.phase==="prep") return 1+Math.max(0,30-state.day)*.012;
    return 2.1+Math.min(1.35,state.survivalDay*.018);
  }
  function itemPrice(item,shop) {
    var s=shop || currentShop();
    var shopRate=s.id==="wholesale"?.82:s.id==="secondhand"?.67:s.id==="online"?.96:1;
    var demand=["主食","肉蛋","饮水","保暖","能源","医疗"].indexOf(item.category)>=0;
    var urgency=demand&&state.phase==="prep"?1+Math.max(0,20-state.day)*.019:1;
    var discount=hasTalent("精打细算")?.92:1;
    if(s.id==="secondhand") shopRate*=1+(1-(state.marketQuality||.88))*.32;
    return Math.max(1,Math.round(item.price*marketMultiplier()*urgency*shopRate*discount));
  }
  function availableStock(shop,item) {
    return state.stock && state.stock[shop.id] ? (state.stock[shop.id][item.id] || 0) : 0;
  }
  function refreshStock() {
    shops.forEach(function(shop){
      items.forEach(function(item){
        if(item.shops.indexOf(shop.id)<0) return;
        var current=availableStock(shop,item);
        var refill=Math.max(1,Math.floor(item.stock*.12));
        state.stock[shop.id][item.id]=Math.min(item.stock*2,current+refill);
      });
    });
  }

  function buildNewState() {
    var origin=origins.find(function(x){return x.id===setup.origin;})||origins[0];
    var mode=difficulties.find(function(x){return x.id===setup.difficulty;})||difficulties[1];
    var starterCash=Math.round(origin.cash*mode.cash);
    var s={
      version:1, name:(setup.name||"林见秋").trim().slice(0,18), origin:origin.id, difficulty:mode.id, talents:setup.talents.slice(0,3),
      phase:"prep",day:1,survivalDay:0,hour:8, money:starterCash,bank:0,
      player:{health:100,hunger:90,thirst:88,stamina:100,sanity:90,bodyTemp:36.5,cleanliness:88,immunity:90},
      skills:{strength:origin.skill==="strength"?origin.level:2,fitness:2,medical:origin.skill==="medical"?origin.level:1,engineering:origin.skill==="engineering"?origin.level:1,cooking:2,farming:origin.skill==="farming"?origin.level:1,trade:origin.skill==="trade"?origin.level:1,exploration:origin.skill==="exploration"?origin.level:1,social:origin.skill==="social"?origin.level:1,survival:2,learning:origin.skill==="learning"?origin.level:1},
      inventory:{warehouse:{},pack:{}},
      packLimit:30,packVolume:0.14,
      house:{type:origin.home,insulation:origin.insulation,defense:22,storage:origin.storage,volume:12,temperature:19,waterCapacity:120,waterStored:38,grid:true,heaterOn:true,generator:false,woodStove:false,fridge:false,freezer:false,greenhouse:false,growLight:false,purifier:false,solar:false,battery:false,batteryCharge:0,ventilation:false,camera:false,workbench:false,nightHeat:0,upgrades:[],rooms:["卧室","厨房","储物间"],appliances:{fridge:false,light:true,radio:false}},
      orders:[],plots:[],contacts:{},event:null,eventIds:[],journal:[],achievements:[],settings:{confirmActions:true,animations:true,textScale:100},age:{},
      counters:{buys:0,unitsBought:0,workDays:0,explores:0,harvests:0,crafts:0,events:0},actions:{workDay:0,studyDay:0},gridFailureDay:0,gameOver:false,gameWon:false,log:[],stock:initStock(),marketQuality:.86
    };
    if(s.talents.indexOf("空间异能")>=0){s.house.storage+=600;s.house.volume+=4;}
    if(s.talents.indexOf("囤货大师")>=0)s.house.storage+=350;
    addToState(s,"warehouse","bottled-water",24);
    addToState(s,"warehouse","emergency-ration",10);
    addToState(s,"warehouse","fish-can",8);
    addToState(s,"warehouse","firewood",16);
    addToState(s,"warehouse","bandage",3);
    addToState(s,"warehouse","down-coat",1);
    addToState(s,"warehouse","tool-kit",1);
    addToState(s,"warehouse","rice",8);
    addToState(s,"warehouse","instant-noodles",5);
    addToState(s,"warehouse","insulation-board",2);
    addToState(s,"warehouse","duct-tape",2);
    addToState(s,"warehouse","waterproof-seal",1);
    if(origin.id==="doctor")addToState(s,"warehouse","medical-kit",1);
    if(origin.id==="farmer"){addToState(s,"warehouse","potato-seeds",3);addToState(s,"warehouse","potting-soil",2);}
    if(origin.id==="engineer"){addToState(s,"warehouse","repair-parts",3);addToState(s,"warehouse","wire",2);}
    if(origin.id==="outdoors"){addToState(s,"warehouse","snow-boots",1);addToState(s,"warehouse","flashlight",1);}
    addStateLog(s,"重生归来","你在末日降临前 30 天醒来。前世记忆还在，时间和资金都有限。","↺");
    return s;
  }
  function addToState(s,place,id,count) {
    s.inventory[place][id]=(s.inventory[place][id]||0)+count;
    s.age[id]=s.age[id]||0;
  }
  function addStateLog(s,title,text,icon) { s.log.unshift({day:s.day,title:title,text:text,icon:icon||"•"}); }

  function renderStart() {
    var originCards=origins.map(function(o){
      return '<button type="button" class="choice-card '+(setup.origin===o.id?"selected":"")+'" data-action="select-origin" data-id="'+o.id+'"><strong>'+o.icon+" "+escapeHtml(o.name)+'</strong><small>'+escapeHtml(o.text)+" ¥"+Math.round(o.cash/10000)+' 万起</small></button>';
    }).join("");
    var difficultyCards=difficulties.map(function(d){
      return '<button type="button" class="choice-card '+(setup.difficulty===d.id?"selected":"")+'" data-action="select-difficulty" data-id="'+d.id+'"><strong>'+escapeHtml(d.name)+'</strong><small>'+escapeHtml(d.desc)+'</small></button>';
    }).join("");
    var talentChips=talents.map(function(t){
      return '<button type="button" class="talent-chip '+(setup.talents.indexOf(t[0])>=0?"selected":"")+'" data-action="toggle-talent" data-id="'+escapeHtml(t[0])+'">'+escapeHtml(t[0])+'</button>';
    }).join("");
    root.innerHTML=
      '<main class="start-shell">'+
        '<section class="start-art"><div class="start-landscape"><div class="cityline"></div><div class="tower t1"></div><div class="tower t2"></div><div class="tower t3"></div><div class="tower t4"></div><div class="tower t5"></div><div class="tower t6"></div><div class="snowbank"></div></div>'+
          '<div class="start-art-copy"><div class="eyebrow">ST★RLIT SURVIVAL SIMULATOR · 01</div><h1>Starlit<br>极寒末世</h1><p>你死于上一场寒冬，又在灾难降临前 30 天醒来。用时间、资金和前世记忆，为这一次准备好一个真正能活下去的家。</p><div class="origin-badges"><span class="tag cold">30 天筹备期</span><span class="tag warm">100 天生存目标</span><span class="tag">离线存档</span></div></div></section>'+
        '<section class="start-panel"><div class="start-card"><div class="eyebrow">NEW RUN · CHARACTER SETUP</div><h2>开始新的周目</h2><p>选择出身、难度和天赋。每个决定都会改变你的起步方式。</p>'+
          '<div class="field"><label for="player-name">幸存者姓名</label><input id="player-name" data-testid="player-name" maxlength="18" value="'+escapeHtml(setup.name)+'" placeholder="输入姓名"></div>'+ 
          '<div class="field"><label>选择出身</label><div class="choice-grid">'+originCards+'</div></div>'+
          '<div class="field"><label>生存难度</label><div class="choice-grid">'+difficultyCards+'</div></div>'+
          '<div class="field"><label>选择天赋 <span class="tiny">（最多 3 项）</span></label><div class="talent-list">'+talentChips+'</div></div>'+
          '<button class="button-primary" type="button" data-testid="start-game" data-action="start-game">带着记忆出发 <span>→</span></button>'+
          '<div class="saved-slot">'+(state?"已有存档：<strong>"+escapeHtml(state.name)+" · "+(state.phase==="prep"?"筹备第 "+state.day+" 天":"末日第 "+state.survivalDay+" 天")+'</strong>　<button class="button-ghost" type="button" data-action="continue-game">继续游戏</button>':"进度自动保存在本机；关闭网页不会消耗游戏时间。")+'</div>'+
        '</div></section>'+
      '</main>';
  }

  function navMarkup(mobile) {
    var choices=mobile?nav.slice(0,5):nav;
    return choices.map(function(item){
      return '<button class="nav-item '+(activePage===item[0]?"active":"")+'" type="button" data-testid="nav-'+item[0]+(mobile?"-mobile":"")+'" data-action="navigate" data-page="'+item[0]+'"><span class="nav-icon">'+item[1]+'</span><span>'+item[2]+'</span></button>';
    }).join("")+(mobile?'<button class="nav-item" type="button" data-testid="nav-more-mobile" data-action="mobile-more"><span class="nav-icon">···</span><span>更多</span></button>':"");
  }
  function gameShell() {
    var shop=currentShop();
    var weather=outsideTemperature();
    var countdown=state.phase==="prep"?Math.max(0,31-state.day):0;
    var saveText=saveBackend==="StarlitApp.storage"?"Starlit 存档已同步":"本机自动存档";
    return '<div class="app-shell">'+
      '<aside class="sidebar"><div class="brand"><div class="brand-mark">✳</div><div><strong>STARLIT</strong><small>FROSTFALL / 01</small></div></div><div><div class="nav-label">避难所行动</div><nav class="nav-list">'+navMarkup(false)+'</nav></div>'+
      '<div class="sidebar-bottom"><div class="supply-mini"><div class="supply-mini-title"><span>当前储备</span><span>⌁</span></div><strong>'+survivalEstimate()+' <small>天</small></strong><div class="progress ice"><i style="width:'+Math.min(100,survivalEstimate()*5)+'%"></i></div><small>按现有口粮与净水估算</small></div><div class="sidebar-status"><i class="dot"></i>'+saveText+'</div></div></aside>'+
      '<main class="main-area"><header class="topbar"><div class="top-context">幸存者档案　/　<strong>'+escapeHtml(state.name)+'</strong></div><div class="top-actions"><div class="top-weather">❄ <span>室外</span> <strong>'+weather+'°C</strong></div><div class="cash-chip">现金 <strong>'+money(state.money)+'</strong></div><div class="profile-dot">'+escapeHtml(state.name.slice(0,1))+'</div></div></header><div class="page-wrap">'+renderPage()+'</div></main>'+
      '<aside class="inspector">'+renderInspector()+'</aside>'+
      '<nav class="mobile-nav">'+navMarkup(true)+'</nav>'+
      '</div>';
  }

  function renderInspector() {
    var p=state.player, inside=insideTemperature(), cold=inside<4;
    var countdown=state.phase==="prep"?Math.max(0,31-state.day):0;
    var tasks=taskList();
    var news=state.log.slice(0,3);
    return '<div class="inspector-kicker">STATUS MONITOR · LIVE</div><h2>生存简报</h2>'+
      '<div class="weather-card"><div class="weather-label">'+(state.phase==="prep"?"寒潮预警 · 城区":"寒潮持续 · 城区")+'</div><div class="weather-number">'+outsideTemperature()+'°<small>C　室外</small></div><p>室内 '+inside+'°C · '+(state.house.grid?"市电稳定":poweredHeat()?"备用电源供暖":"供电中断")+" · "+(cold?"低温风险":"环境可控")+'</p></div>'+
      '<div class="survival-day"><strong>'+(state.phase==="prep"?"筹备期":"生存期")+'</strong><small>'+(state.phase==="prep"?"末日倒计时":"极寒第 "+state.survivalDay+" 天")+'</small></div>'+
      (state.phase==="prep"?'<div class="countdown"><span>距离寒潮抵达</span><strong>D−'+countdown+'</strong></div>':'<div class="countdown"><span>百日生存目标</span><strong>'+state.survivalDay+' / 100</strong></div>')+
      '<div class="stat-list">'+statRow("生命",p.health,"♥","red")+statRow("饱食",p.hunger,"◉","warm")+statRow("水分",p.thirst,"◌","ice")+statRow("体力",p.stamina,"ϟ","green")+statRow("精神",p.sanity,"✧","")+'</div>'+
      '<div class="right-section"><div class="right-section-head"><strong>当前目标</strong><button type="button" data-action="navigate" data-page="journal">全部目标 →</button></div>'+
        tasks.map(function(t){return '<div class="task-row '+(t.done?"done":"")+'"><span class="task-check">'+(t.done?"✓":"")+'</span><span>'+escapeHtml(t.text)+'</span></div>';}).join("")+
      '</div><div class="right-section"><div class="right-section-head"><strong>最近情报</strong><button type="button" data-action="navigate" data-page="journal">记录 →</button></div>'+
        news.map(function(n){return '<div class="news-item"><span class="news-icon">'+escapeHtml(n.icon||"•")+'</span><div><strong>'+escapeHtml(n.title)+'</strong><small>第 '+n.day+' 天 · '+escapeHtml(n.text.slice(0,30))+'</small></div></div>';}).join("")+'</div>';
  }
  function statRow(label,value,icon,color) {
    var num=Math.round(clamp(value,0,100));
    return '<div class="stat-row"><label>'+icon+'　'+label+'</label><strong>'+num+'%</strong><div class="progress '+(color||"")+'"><i style="width:'+num+'%"></i></div></div>';
  }
  function survivalEstimate() {
    var food=Object.keys(state.inventory.warehouse||{}).concat(Object.keys(state.inventory.pack||{})).reduce(function(total,id){
      var item=getItem(id); return item.effect.kind==="food"&&!item.effect.raw?total+inventoryTotal(id):total;
    },0);
    var water=inventoryTotal("bottled-water")+inventoryTotal("large-water")+inventoryTotal("mineral-water");
    return Math.max(0,Math.min(100,Math.floor(Math.min(food/(state.phase==="prep"?1:2),water/(state.phase==="prep"?1:2)))));
  }
  function outsideTemperature() {
    if(state.phase==="prep") return Math.round(17-(state.day-1)*.83);
    return Math.round(Math.max(-76,-35-(state.survivalDay-1)*.68));
  }
  function poweredHeat() {
    if(state.house.heatingActive!=null) return !!state.house.heatingActive;
    if(state.house.grid && state.house.heaterOn) return true;
    if(state.house.generator && state.house.heaterOn && inventoryTotal("diesel")>0) return true;
    if(state.house.woodStove && state.house.heaterOn && inventoryTotal("firewood")>0) return true;
    return false;
  }
  function insideTemperature() {
    if(state.phase==="prep") return Math.round(clamp(20+(state.house.insulation-55)*.06,15,23));
    var outdoor=outsideTemperature(), insulated=outdoor+(22-outdoor)*state.house.insulation/100;
    var heat=poweredHeat()?18:0;
    return Math.round(clamp(insulated+heat+state.house.nightHeat+(state.house.heaterBonus||0),-80,25));
  }
  function phaseLabel() { return state.phase==="prep"?"末日前筹备期":"极寒生存期"; }
  function taskList() {
    if(state.phase==="prep") return [
      {text:"备足饮水与可即食口粮",done:inventoryTotal("bottled-water")>=12&&inventoryTotal("emergency-ration")+inventoryTotal("fish-can")>=8},
      {text:"为安全屋增加保温层",done:hasUpgrade("insulation")||state.house.insulation>=74},
      {text:"检查应急燃料储备",done:inventoryTotal("firewood")+inventoryTotal("diesel")>=18||hasUpgrade("generator")}
    ];
    return [
      {text:"保持室温高于 5°C",done:insideTemperature()>=5},
      {text:"未来三天饮水不断供",done:inventoryTotal("bottled-water")+inventoryTotal("large-water")>=6},
      {text:"距离百日生存目标 "+Math.max(0,100-state.survivalDay)+" 天",done:state.survivalDay>=100}
    ];
  }

  function renderPage() {
    if(state.gameOver||state.gameWon) return renderEnding();
    var pages={overview:renderOverview,market:renderMarket,warehouse:renderWarehouse,shelter:renderShelter,explore:renderExplore,people:renderPeople,journal:renderJournal,settings:renderSettings};
    return (pages[activePage]||renderOverview)();
  }
  function pageHeading(eyebrow,title,description,actions) {
      return '<div class="page-heading"><div><div class="eyebrow">'+eyebrow+'</div><h1>'+title+'</h1><p>'+description+'</p></div><div class="heading-actions">'+(actions||"")+'</div></div>';
  }
  function card(title,body,extra,sub) {
    return '<section class="card '+(extra||"")+'"><div class="card-head"><h2>'+title+'</h2>'+(sub?'<small>'+sub+'</small>':"")+'</div><div class="card-body">'+body+'</div></section>';
  }
  function render() {
    if(!root) return;
    if(!state){activePage="start";renderStart();return;}
    if(!state.version)state.version=1;
    if(activePage==="start")activePage="overview";
    root.innerHTML=gameShell();
  }

  function renderOverview() {
    var countdown=state.phase==="prep"?Math.max(0,31-state.day):0;
    var weatherText=state.phase==="prep"?(state.day>23?"寒潮已近，城市气温急速下降。":"还有时间，但市场供应正在收紧。"):"暴雪封锁街道，供电和物流已逐步中断。";
    var load=inventoryLoad("warehouse"), cap=storageCapacity(), fullness=Math.round(load.weight/cap.weight*100);
    var actionButtons='<button type="button" class="button-secondary" data-action="work">安排工作 + 现金</button><button type="button" class="button-secondary" data-action="rest">休息恢复</button><button type="button" class="button-primary" data-action="end-day">结束当天 →</button>';
    var hero='<section class="card hero-card"><div class="hero-content"><div class="hero-date" data-testid="phase-countdown">DAY '+String(state.day).padStart(2,"0")+' · '+phaseLabel().toUpperCase()+'</div><h2>'+(state.phase==="prep"?"暴风雪还有 "+countdown+" 天。":"极寒第 "+state.survivalDay+" 天：今晚会更冷。")+'</h2><p>'+weatherText+' 每个行动都会消耗时间；你的下一步，决定这个家能撑多久。</p><div class="hero-stats"><div class="hero-stat"><small>当前时间</small><strong>'+formatHour(state.hour)+'</strong></div><div class="hero-stat"><small>室外温度</small><strong>'+outsideTemperature()+'°C</strong></div><div class="hero-stat"><small>住所</small><strong>'+escapeHtml(state.house.type)+'</strong></div></div><button type="button" class="button-primary" data-testid="end-day" data-action="end-day">'+(state.phase==="prep"?"准备就绪，结束今天":"熄灯休息，进入明天")+' <span>→</span></button></div><div class="weather-illustration"><div class="house-silhouette"></div><div class="house-window"></div><div class="snow-ground"></div></div></section>';
    var kpis='<div class="kpi-grid">'+kpi("现金","¥"+Math.round(state.money/10000*10)/10+"万","可支配资金","◈")+kpi("食物储备",foodCount()+" 份","约 "+Math.floor(foodCount()/(state.phase==="prep"?1:2))+" 天口粮","▧")+kpi("饮用水",waterCount()+" L","估算 "+Math.floor(waterCount()/(state.phase==="prep"?1:2))+" 天","◌")+kpi("仓储占用",fullness+"%","已用 "+Math.round(load.weight)+" / "+cap.weight+" kg","▤")+kpi("安全屋保温",state.house.insulation+"%","室内 "+insideTemperature()+"°C","⌂")+kpi("社区关系",Object.keys(state.contacts).length+" 人","可联系幸存者","♧")+'</div>';
    var room='<section class="card"><div class="card-head"><h2>安全屋一览</h2><button class="button-ghost" type="button" data-action="navigate" data-page="shelter">改造 →</button></div><div class="room-scene"><span class="room-light"></span><span class="room-shelf"></span><span class="room-box">📦 🥫</span></div><div class="card-body"><div class="house-metrics"><div class="house-metric"><small>室内温度</small><strong>'+insideTemperature()+'°C</strong></div><div class="house-metric"><small>保温 / 防御</small><strong>'+state.house.insulation+' / '+state.house.defense+'</strong></div><div class="house-metric"><small>电力</small><strong>'+(state.house.grid?"城市电网":"独立供电")+'</strong></div><div class="house-metric"><small>储水量</small><strong>'+state.house.waterStored+' / '+state.house.waterCapacity+' L</strong></div></div></div></section>';
    var next='<section class="card"><div class="card-head"><h2>今日行动</h2><small>'+Math.max(0,22-state.hour)+' 小时可用</small></div><div class="card-body"><p class="muted" style="font-size:12px;margin:0 0 13px">工作可补充资金；整理物资、购物和施工会消耗时间。状态面板会同步显示生存风险。</p><div class="heading-actions">'+actionButtons+'</div><div class="divider"></div><div class="list-row"><div class="row-main"><span class="item-icon">🧾</span><div><strong>前世记忆</strong><small>城市气象台将在第 30 天前发布最终寒潮警报。</small></div></div><span class="tag cold">重生记忆</span></div></div></section>';
    var event=state.event?renderEventCard():"";
    return pageHeading("COMMAND CENTER · "+phaseLabel(), "你好，"+escapeHtml(state.name), "寒冬还没到来。把有限的时间用在真正重要的准备上。",'<button class="button-secondary" type="button" data-action="news">查看新闻 · 10 分钟</button>')+
      '<div class="overview-grid"><div>'+hero+kpis+'</div><div class="side-stack">'+room+next+event+'</div></div>';
  }
  function kpi(title,value,note,icon) {
    return '<div class="card kpi-card"><div class="kpi-top"><span>'+title+'</span><span class="kpi-icon">'+icon+'</span></div><strong>'+value+'</strong><small>'+note+'</small></div>';
  }
  function formatHour(hour) {
    var whole=Math.floor(hour),minute=Math.round((hour-whole)*60);
    return String(whole%24).padStart(2,"0")+":"+String(minute).padStart(2,"0");
  }
  function foodCount() {
    return Object.keys(state.inventory.warehouse).concat(Object.keys(state.inventory.pack)).reduce(function(total,id){
      var item=getItem(id);return item.effect.kind==="food"&&!item.effect.raw?total+inventoryTotal(id):total;
    },0);
  }
  function waterCount() {
    return Math.round(inventoryTotal("bottled-water")*.6+inventoryTotal("large-water")*5+inventoryTotal("mineral-water")*.55+state.house.waterStored);
  }
  function renderMarket() {
    var shopTabs=shops.map(function(shop){
      return '<button type="button" class="shop-tab '+(shopId===shop.id?"active":"")+'" data-action="select-shop" data-id="'+shop.id+'">'+shop.icon+" "+shop.name+'</button>';
    }).join("");
    var categories=Array.from(new Set(items.map(function(i){return i.category;}))).sort();
    var categoryOptions='<option value="全部">全部分类</option>'+categories.map(function(c){return '<option value="'+escapeHtml(c)+'" '+(marketCategory===c?"selected":"")+'>'+escapeHtml(c)+'</option>';}).join("");
    var sortOptions=["推荐","价格从低到高","价格从高到低","重量从轻到重"].map(function(s){return '<option '+(marketSort===s?"selected":"")+'>'+s+'</option>';}).join("");
    var selected=currentShop();
    var cartInfo=cartSummary();
    var freight=selected.id==="online"?0:(window._deliveryMode?selected.freight:0);
    var filter='<div class="filterbar"><input id="market-search" placeholder="搜索物资名称或用途" value="'+escapeHtml(marketSearch)+'" aria-label="搜索物资"><select id="market-category" aria-label="分类">'+categoryOptions+'</select><select id="market-sort" aria-label="排序">'+sortOptions+'</select><span class="tag">'+items.length+' 种物资</span></div>';
    var channels='<section class="card"><div class="card-head"><h2>采购渠道</h2><small>每家商户独立库存 · 物价随寒潮变化</small></div><div class="card-body">'+filter+'</div><div class="shop-tabs">'+shopTabs+'</div><div class="card-body" style="padding-top:0"><div class="list-row"><div class="row-main"><span class="item-icon">'+selected.icon+'</span><div><strong>'+escapeHtml(selected.name)+'</strong><small>'+escapeHtml(selected.place)+' · '+selected.kind+(selected.id==="online"?" · 配送 1–3 天":" · 采购耗时约 "+selected.hours+" 小时")+'</small></div></div><span class="tag warm">'+(selected.id==="online"?"物流可能延迟":"限量库存")+'</span></div></div></section>';
    var results='<section class="card" style="margin-top:14px"><div class="card-head"><h2>今日货架</h2><small>标价会按当前市场倍率计算</small></div><div class="card-body"><div id="market-results">'+renderMarketCards()+'</div></div></section>';
    var delivery=selected.id==="online"?"":'<label class="tag cold" style="gap:8px"><input id="delivery-mode" type="checkbox" '+(window._deliveryMode?"checked":"")+' style="appearance:checkbox;min-height:17px;width:17px;accent-color:#e9ad61"> 城配入库（运费 ¥'+selected.freight+'）</label>';
    var dock='<div class="cart-dock"><div class="cart-info"><span class="item-icon">🛒</span><div><strong>'+cartInfo.count+' 件 · '+money(cartInfo.total+freight)+'</strong><small>'+Math.round(cartInfo.weight*10)/10+' kg · '+Math.round(cartInfo.volume*100)/100+' m³ · '+(selected.id==="online"?"预计 "+cartInfo.eta+" 天到货":window._deliveryMode?"配送入库":"自提入包")+'</small></div></div>'+delivery+'<button class="button-primary" type="button" data-testid="checkout-cart" data-action="checkout">结算购物车</button></div>';
    return pageHeading("SUPPLY NETWORK · "+(state.phase==="prep"?"价格还在上涨":"补给网络已中断"),"采购市场","搜寻、比价并将物资运回安全屋。物资有重量、体积与库存限制。",'<button class="button-secondary" type="button" data-action="navigate" data-page="warehouse">查看仓库</button>')+channels+results+dock;
  }
  function renderMarketCards() {
    var shop=currentShop();
    var list=items.filter(function(item){
      return item.shops.indexOf(shop.id)>=0 &&
        (marketCategory==="全部"||item.category===marketCategory) &&
        (!marketSearch||item.name.toLowerCase().indexOf(marketSearch.toLowerCase())>=0||item.description.toLowerCase().indexOf(marketSearch.toLowerCase())>=0);
    });
    list.sort(function(a,b){
      if(marketSort==="价格从低到高")return itemPrice(a,shop)-itemPrice(b,shop);
      if(marketSort==="价格从高到低")return itemPrice(b,shop)-itemPrice(a,shop);
      if(marketSort==="重量从轻到重")return a.weight-b.weight;
      return a.category.localeCompare(b.category,"zh-CN")||a.price-b.price;
    });
    if(!list.length)return '<div class="empty-state"><span class="empty-icon">🔎</span><strong>没有找到合适物资</strong><p>试试更短的关键词或切换采购渠道。</p></div>';
    return '<div class="item-grid">'+list.map(function(item){
      var stock=availableStock(shop,item),price=itemPrice(item,shop),inCart=cart[item.id]||0;
      var bars=priceBars(item,shop);
      return '<article class="product-card"><div class="product-top"><div class="product-icon">'+item.icon+'</div><span class="tag">'+escapeHtml(item.category)+'</span></div><h3>'+escapeHtml(item.name)+'</h3><p>'+escapeHtml(item.description)+'</p><div class="product-facts"><span>库存 '+stock+'</span><span>'+item.weight+' kg</span><span>'+item.volume+' m³</span>'+(item.shelf<9999?'<span>保质 '+item.shelf+' 天</span>':"")+'</div><div class="price-bars" aria-label="价格走势">'+bars+'</div><div class="product-bottom"><div class="product-price">'+money(price)+'<small>历史基价 '+money(item.price)+'</small></div><div class="quantity-control"><input type="number" id="qty-'+item.id+'" min="1" max="'+Math.max(1,stock)+'" value="'+(inCart||1)+'" aria-label="'+escapeHtml(item.name)+'购买数量"><button type="button" class="button-secondary" data-testid="add-item-'+item.id+'" data-action="add-cart" data-id="'+item.id+'" '+(stock<=0?"disabled":"")+'>加入</button></div></div></article>';
    }).join("")+'</div>';
  }
  function priceBars(item,shop) {
    var seed=hashText(item.id+shop.id);
    var vals=[];
    for(var i=0;i<7;i++)vals.push(28+((seed+i*19)%54));
    return vals.map(function(v,i){return '<i class="'+(i===6?"today":"")+'" style="height:'+v+'%"></i>';}).join("");
  }
  function cartSummary() {
    var ids=Object.keys(cart).filter(function(id){return cart[id]>0;});
    return ids.reduce(function(a,id){var it=getItem(id),q=cart[id];a.count+=q;a.total+=itemPrice(it)*q;a.weight+=it.weight*q;a.volume+=it.volume*q;return a;},{count:0,total:0,weight:0,volume:0,eta:currentShop().id==="online"?2:0});
  }
  function renderWarehouse() {
    var allIds=Array.from(new Set(Object.keys(state.inventory.warehouse).concat(Object.keys(state.inventory.pack))));
    if(warehouseSearch)allIds=allIds.filter(function(id){var it=getItem(id);return it.name.indexOf(warehouseSearch)>=0||it.category.indexOf(warehouseSearch)>=0;});
    allIds.sort(function(a,b){return getItem(a).category.localeCompare(getItem(b).category,"zh-CN")||getItem(a).name.localeCompare(getItem(b).name,"zh-CN");});
    var storeLoad=inventoryLoad("warehouse"),packLoad=inventoryLoad("pack"),cap=storageCapacity(),foodDays=Math.floor(foodCount()/(state.phase==="prep"?1:2)),waterDays=Math.floor(waterCount()/(state.phase==="prep"?1:2));
    var rows=allIds.map(function(id){
      var item=getItem(id),w=inventoryCount("warehouse",id),p=inventoryCount("pack",id),fresh=item.shelf<9999?Math.max(0,item.shelf-(state.age[id]||0)):"—";
      var action='<div class="inv-actions">'+(w?'<button type="button" class="button-secondary" data-action="use-item" data-id="'+id+'" data-place="warehouse">使用</button><button type="button" data-action="move-item" data-id="'+id+'" data-from="warehouse">装入背包</button>':"")+(p?'<button type="button" data-action="move-item" data-id="'+id+'" data-from="pack">收回仓库</button>':"")+(state.phase==="prep"?'<button type="button" class="button-ghost" data-action="sell-item" data-id="'+id+'" data-place="'+(w?"warehouse":"pack")+'">出售</button>':"")+'</div>';
      return '<div class="inventory-row"><div class="row-main"><span class="item-icon">'+item.icon+'</span><div><strong>'+escapeHtml(item.name)+'</strong><small>'+escapeHtml(item.category)+' · '+escapeHtml(item.description)+'</small></div></div><div class="inv-qty">'+(w+p)+' 件 <span class="tiny">（库 '+w+' / 包 '+p+'）</span></div><div class="inv-weight">'+Math.round((w+p)*item.weight*10)/10+' kg</div><div class="inv-fresh">'+(fresh==="—"?"—":fresh+" 天")+'</div>'+action+'</div>';
    }).join("");
    var content=rows||'<div class="empty-state"><span class="empty-icon">📦</span><strong>仓库还没有物资</strong><p>去采购市场买下第一批补给。物资进库后会在这里显示重量和保质期。</p><button class="button-primary" data-action="navigate" data-page="market">打开市场</button></div>';
    var stats='<div class="warehouse-summary">'+summaryCard("仓库占用",Math.round(storeLoad.weight)+" <span>/ "+cap.weight+" kg</span>",Math.round(storeLoad.weight/cap.weight*100)+"% 容量") +summaryCard("随身背包",Math.round(packLoad.weight)+" <span>/ "+state.packLimit+" kg</span>",Math.round(packLoad.volume*100)/100+" m³") +summaryCard("食物余量",foodDays+" <span>天</span>",foodCount()+" 份可食用口粮") +summaryCard("饮水余量",waterDays+" <span>天</span>",waterCount()+" L 含储水")+'</div>';
    var table='<section class="card"><div class="card-head"><h2>物资清单</h2><div class="filterbar"><input id="warehouse-search" placeholder="搜索名称或分类" value="'+escapeHtml(warehouseSearch)+'" aria-label="搜索仓库物资"><span class="tag">'+allIds.length+' 种物资</span></div></div><div class="card-body" style="padding-top:12px"><div class="table-head"><span>物资</span><span>数量</span><span>重量</span><span>新鲜度</span><span>操作</span></div>'+content+'</div></section>';
    var orders=state.orders.length?'<section class="card" style="margin-top:14px"><div class="card-head"><h2>运输中的订单</h2><small>'+state.orders.length+' 笔待送达</small></div><div class="card-body">'+state.orders.map(function(o){return '<div class="list-row"><div class="row-main"><span class="item-icon">🚚</span><div><strong>'+escapeHtml(o.name)+'</strong><small>'+o.lines.map(function(l){return escapeHtml(getItem(l.id).name)+" ×"+l.qty;}).join("、")+'</small></div></div><span class="tag warm">预计 '+Math.max(1,o.daysLeft)+' 天</span></div>';}).join("")+'</div></section>':"";
    return pageHeading("INVENTORY · "+Math.round(storeLoad.weight)+" KG STORED","物资仓库","背包和安全屋库存分开计算。转移、食用和出售都会实际改变数量。",'<button class="button-secondary" type="button" data-action="navigate" data-page="market">补充物资</button>')+stats+table+orders;
  }
  function summaryCard(title,value,note) { return '<div class="card summary-card"><small>'+title+'</small><strong>'+value+'</strong><small>'+note+'</small></div>'; }
  function renderShelter() {
    var house=state.house;
    var currentPower=house.grid?"城市电网":(house.generator&&inventoryTotal("diesel")>0?"柴油发电机":house.solar?"太阳能与储能":"无供电");
    var heatButton='<button type="button" class="'+(house.heaterOn?"button-secondary":"button-danger")+'" data-action="toggle-heater">取暖 '+(house.heaterOn?"开启":"关闭")+'</button>';
    var metrics='<div class="warehouse-summary">'+summaryCard("室内温度",insideTemperature()+"°C",outsideTemperature()+"°C 室外") +summaryCard("保温系数",house.insulation+"%","墙体、密封与窗帘") +summaryCard("防御等级",house.defense,"夜间事件影响降低") +summaryCard("供电状态",currentPower,house.grid?"市电暂时稳定":poweredHeat()?"供暖燃料可用":"需要备用电源")+'</div>';
    var upgradeCards=upgrades.map(function(u){
      var built=hasUpgrade(u.id),can=hasMaterials(u.materials)&&state.money>=u.cost;
      return '<article class="upgrade-card '+(built?"built":"")+'"><div class="product-top"><div class="product-icon">'+u.icon+'</div><span class="tag '+(built?"cold":"")+'">'+(built?"已安装":u.hours+" 小时")+'</span></div><h3>'+escapeHtml(u.name)+'</h3><p>'+escapeHtml(u.desc)+'</p><div class="product-facts"><span>'+money(u.cost)+'</span><span>'+escapeHtml(materialLabel(u.materials))+'</span></div>'+(built?'<button class="button-secondary" type="button" disabled>设施已投入使用</button>':'<button class="'+(can?"button-primary":"button-secondary")+'" type="button" data-action="build-upgrade" data-id="'+u.id+'">施工改造</button>')+'</article>';
    }).join("");
    var appliances='<div class="list-rows">'+[
      ["heaterOn","电暖器","维持室温；停电后需发电机或柴炉。",house.heaterOn],
      ["fridge","冷藏保鲜","有电时延长鲜食保质期。",house.fridge],
      ["purifier","净水设备","有电时每日净化 8 L 储水。",house.purifier],
      ["ventilation","通风系统","降低清洁度与免疫状态的损耗。",house.ventilation]
    ].map(function(d){return '<div class="setting-row"><div><strong>'+d[1]+'</strong><small>'+d[2]+'</small></div><button type="button" class="toggle '+(d[3]?"on":"")+'" data-action="toggle-device" data-id="'+d[0]+'" aria-label="切换'+d[1]+'"></button></div>';}).join("")+'</div>';
    var cooking=recipes.map(function(r){
      var enough=Object.keys(r.need).every(function(id){return inventoryTotal(id)>=r.need[id];});
      return '<div class="list-row"><div class="row-main"><span class="item-icon">'+r.icon+'</span><div><strong>'+escapeHtml(r.name)+'</strong><small>'+escapeHtml(r.desc)+' · '+r.hours+' 小时 · '+escapeHtml(materialLabel(r.need))+'</small></div></div><button type="button" class="'+(enough?"button-primary":"button-secondary")+'" data-action="cook" data-id="'+r.id+'" '+(enough?"":"disabled")+'>'+(enough?"烹饪":"缺少食材")+'</button></div>';
    }).join("");
    var greenhouse=house.greenhouse?renderPlots():'<div class="empty-state"><span class="empty-icon">🌱</span><strong>还没有室内种植区</strong><p>安装“室内温室”后可以播种，在安全屋内生产新鲜蔬菜。</p></div>';
    return pageHeading("SAFEHOUSE · "+escapeHtml(house.type),"把房子变成安全屋","升级保温、供电、仓储与种植设施；为断电后的漫长寒冬留好余地。",heatButton)+metrics+
      '<div class="two-col"><div>'+card("建筑与安全升级",'<div class="upgrade-grid">'+upgradeCards+'</div>')+'</div><div class="side-stack">'+card("用电设备与供暖",appliances,"","断电后设备按燃料和优先级运行")+card("室内厨房与烹饪",cooking,"","烹饪消耗食材、净水和时间")+card("种植室",greenhouse,"","收获会进入安全屋仓库")+'</div></div>';
  }
  function renderPlots() {
    var plots=state.plots||[];
    var plotHtml=plots.map(function(p,index){
      return '<div class="list-row"><div class="row-main"><span class="item-icon">'+getItem(p.seed).icon+'</span><div><strong>'+escapeHtml(getItem(p.seed).name)+" · "+escapeHtml(getItem(p.crop).name)+'</strong><small>生长 '+Math.max(0,p.daysLeft)+' 天 · 预计收获 '+p.yield+' 份</small></div></div><span class="tag '+(p.daysLeft<=0?"cold":"")+'">'+(p.daysLeft<=0?"可收获":"生长中")+'</span>'+(p.daysLeft<=0?'<button type="button" class="button-primary" data-action="harvest" data-index="'+index+'">收获</button>':"")+'</div>';
    }).join("");
    return (plots.length?plotHtml:'<p class="muted" style="font-size:11px">种植位空闲。播种需要种子与培养土。</p>')+'<button type="button" class="button-secondary" data-action="plant">播种</button>';
  }
  function renderExplore() {
    var map=locations.map(function(loc){
      var found=state.journal.some(function(j){return j.type==="location"&&j.id===loc.id;});
      return '<article class="location-card"><span class="tag '+(loc.risk>30?"danger":"cold")+'">'+loc.icon+" 风险 "+loc.risk+'%</span><h3>'+escapeHtml(loc.name)+'</h3><p>'+escapeHtml(loc.desc)+'</p><button type="button" class="button-secondary" data-action="explore" data-id="'+loc.id+'">'+(found?"再次搜寻":"前往探索")+" · "+loc.hours+'h</button></article>';
    }).join("");
    var status=state.phase==="prep"?"末日前道路仍然通畅，探索可带回额外收入和建材。":"风雪中外出会损耗体温；装备与技能能降低风险。";
    var event=state.event?renderEventCard():"";
    return pageHeading("CITY MAP · "+(state.phase==="prep"?"SUPPLY RUN":"WHITEOUT"),"城市探索","选择地点外出搜寻物资。旅程消耗时间和体力，风险会受技能、装备及天气影响。",'<span class="tag cold">探索技能 '+(state.skills.exploration||1)+'</span>')+
      '<section class="card"><div class="card-head"><h2>城区地图</h2><small>'+status+'</small></div><div class="card-body"><div class="map-grid">'+map+'</div></div></section>'+event;
  }
  function renderPeople() {
    var cards=npcs.map(function(npc){
      var contact=state.contacts[npc.id],known=!!contact,met=known?contact.met:false,joined=known&&contact.joined;
      var level=known?contact.affinity:0;
      var action=joined?'<span class="tag cold">同行中 · 每日额外消耗口粮</span>':known?'<button type="button" class="button-secondary" data-action="talk" data-id="'+npc.id+'">拜访交流 · 1h</button><button type="button" class="button-primary" data-action="invite" data-id="'+npc.id+'">'+(state.phase==="prep"?"邀请加入":"邀请同行")+'</button>':'<button type="button" class="button-secondary" data-action="meet-npc" data-id="'+npc.id+'">建立联系 · 1h</button>';
      return '<article class="npc-card"><div class="npc-avatar">'+npc.icon+'</div><div style="flex:1"><h3>'+escapeHtml(npc.name)+' <span class="tiny">· '+escapeHtml(npc.role)+'</span></h3><p>'+escapeHtml(npc.text)+'</p><div class="product-facts"><span>专长：'+skillName(npc.skill)+'</span>'+(known?'<span>信任 '+level+' / 100</span>':"")+'</div><div class="heading-actions">'+action+'</div></div></article>';
    }).join("");
    var team=Object.keys(state.contacts).filter(function(id){return state.contacts[id].joined;}).length;
    var current=state.phase==="prep"?"末日前的人情与技能关系会转化为关键渠道。":"幸存者加入后可分担工作，但每日食物和饮水消耗增加。";
    return pageHeading("SURVIVOR NETWORK · "+team+" ALLIES","幸存者与关系","找到可靠的邻居、医生和工程师。帮助与信任会带来真实资源，也会增加责任。",'<span class="tag">同行 '+team+' 人</span>')+
      card("城市中的联系人",'<p class="muted" style="font-size:11px;margin:0 0 12px">'+current+'</p><div class="three-col">'+cards+'</div>');
  }
  function skillName(key) {
    return ({medical:"医疗",engineering:"工程",exploration:"探索",trade:"交易",farming:"种植",social:"社交"})[key]||key;
  }
  function renderEventCard() {
    var ev=state.event;
    if(!ev||ev.resolved)return "";
    return '<section class="event-card" style="margin-top:14px"><div class="eyebrow">WORLD EVENT · DAY '+state.day+'</div><h3>'+ev.icon+" "+escapeHtml(ev.title)+'</h3><p>'+escapeHtml(ev.text)+'</p><div class="event-options"><button type="button" class="button-primary" data-action="event-choice" data-id="a">'+escapeHtml(ev.a)+'</button><button type="button" class="button-secondary" data-action="event-choice" data-id="b">'+escapeHtml(ev.b)+'</button></div></section>';
  }
  function renderJournal() {
    var tasks=taskList();
    var taskHtml=tasks.map(function(t){return '<div class="list-row"><div class="row-main"><span class="task-check">'+(t.done?"✓":"")+'</span><div><strong>'+escapeHtml(t.text)+'</strong><small>'+(t.done?"已完成":"尚未完成")+'</small></div></div><span class="tag '+(t.done?"cold":"")+'">'+(t.done?"完成":"主线")+'</span></div>';}).join("");
    var ach=achievements.map(function(a){var got=state.achievements.indexOf(a.id)>=0;return '<div class="list-row"><div class="row-main"><span class="item-icon">'+(got?"🏅":"🔒")+'</span><div><strong>'+escapeHtml(a.name)+'</strong><small>'+escapeHtml(a.desc)+'</small></div></div><span class="tag '+(got?"warm":"")+'">'+(got?"已解锁":"未完成")+'</span></div>';}).join("");
    var history=state.log.map(function(l){return '<div class="journal-entry"><div class="journal-day">第 '+l.day+' 天</div><div class="journal-mark">'+escapeHtml(l.icon||"•")+'</div><div><strong>'+escapeHtml(l.title)+'</strong><p>'+escapeHtml(l.text)+'</p></div></div>';}).join("");
    return pageHeading("FIELD NOTES · "+state.log.length+" ENTRIES","日志与目标","记录行动、事件与生存节点。主线进度和成就会随游戏状态自动更新。",'<button type="button" class="button-secondary" data-action="news">阅读今日新闻</button>')+
      '<div class="two-col">'+card("阶段目标",taskHtml)+card("生存成就",ach)+'</div><section class="card" style="margin-top:14px"><div class="card-head"><h2>行动记录</h2><small>自动保存最近 80 条</small></div><div class="card-body"><div class="journal-list">'+(history||'<div class="empty-state"><strong>暂无记录</strong></div>')+'</div></div></section>';
  }
  function renderSettings() {
    var sound=state.settings.sound!==false,animations=state.settings.animations!==false;
    var backend=saveBackend==="StarlitApp.storage"?"Starlit 专属 JSON 存档":"浏览器本机存档";
    return pageHeading("PREFERENCES · LOCAL SAVE","设置与存档","进度保存在 Starlit Workspace 的应用存档中；关闭应用时，游戏时间暂停。",'<button type="button" class="button-primary" data-action="save-now">立即保存</button>')+
      '<div class="settings-grid">'+
        card("界面偏好",'<div class="list-rows"><div class="setting-row"><div><strong>动效</strong><small>减少界面切换动效。</small></div><button type="button" class="toggle '+(animations?"on":"")+'" data-action="setting-toggle" data-id="animations" aria-label="切换动效"></button></div><div class="setting-row"><div><strong>行动确认</strong><small>高风险探索和结束当天前显示确认。</small></div><button type="button" class="toggle '+(state.settings.confirmActions?"on":"")+'" data-action="setting-toggle" data-id="confirmActions" aria-label="切换行动确认"></button></div><div class="setting-row"><div><strong>游戏字号</strong><small>当前 '+state.settings.textScale+'%</small></div><button type="button" class="button-secondary" data-action="font-size">调整</button></div></div>')+
        card("游戏存档",'<div class="list-rows"><div class="setting-row"><div><strong>自动保存</strong><small>'+backend+' · key '+STORAGE_KEY+'</small></div><span class="tag cold">已启用</span></div><div class="setting-row"><div><strong>导出存档</strong><small>下载完整状态 JSON，可在另一设备导入。</small></div><button type="button" class="button-secondary" data-action="export-save">导出</button></div><div class="setting-row"><div><strong>导入存档</strong><small>导入后替换当前游戏进度。</small></div><button type="button" class="button-secondary" data-action="import-save">选择文件</button></div><div class="setting-row"><div><strong>删除本周目</strong><small>清空存档并重新创建角色。</small></div><button type="button" class="button-danger" data-action="reset-save">重新开始</button></div></div>')+
        card("运行环境",'<div class="list-rows"><div class="setting-row"><div><strong>当前运行环境</strong><small>应用由 Starlit Workspace 打开，优先使用 Android 原生 JSON 存档桥。</small></div><span class="tag '+(storageBridge()?"cold":"")+'">'+(storageBridge()?"Starlit WebView":"本机浏览器")+'</span></div><div class="setting-row"><div><strong>内容规模</strong><small>'+items.length+' 种有用途物资 · 10 个渠道 · 15 个探索地点</small></div><span class="tag">离线可用</span></div></div>')+
        card("运行规则",'<p class="muted" style="font-size:11px;margin:0">关闭应用时游戏暂停。行动按游戏时间推进；跨天会结算食物、饮水、能源、温度、状态、订单、物价与作物。游戏属性用于虚构生存模拟，不代表现实医疗建议。</p><div class="divider"></div><div class="list-row"><div class="row-main"><span class="item-icon">❄️</span><div><strong>Starlit极寒末世：重生囤货</strong><small>生存目标：筹备 30 天，末日后生存 100 天</small></div></div><span class="tag warm">v1.0</span></div>')
      +'</div><input id="save-file-input" type="file" accept="application/json,.json" hidden>';
  }
  function renderEnding() {
    var ending=endingProfile();
    return '<section class="card" style="max-width:760px;margin:8vh auto;padding:clamp(24px,6vw,60px);text-align:center"><div class="eyebrow" style="justify-content:center">'+(state.gameWon?"SURVIVAL COMPLETE":"RUN ENDED")+' · '+escapeHtml(ending.label)+'</div><div style="font-size:50px;margin:18px 0">'+ending.icon+'</div><h1 style="font-size:clamp(30px,5vw,48px);margin:0 0 12px">'+escapeHtml(ending.title)+'</h1><p class="muted" style="max-width:500px;margin:0 auto 22px">'+escapeHtml(ending.message)+'</p><div class="house-metrics" style="max-width:430px;margin:0 auto 24px"><div class="house-metric"><small>已准备天数</small><strong>'+Math.min(30,state.day-1)+'</strong></div><div class="house-metric"><small>末日生存天数</small><strong>'+state.survivalDay+'</strong></div><div class="house-metric"><small>完成改造</small><strong>'+state.house.upgrades.length+' 项</strong></div><div class="house-metric"><small>建立关系</small><strong>'+Object.keys(state.contacts).length+' 人</strong></div></div><button class="button-primary" type="button" data-action="new-run">开启新周目</button> <button class="button-secondary" type="button" data-action="navigate" data-page="journal">回顾记录</button></section>';
  }
  function endingProfile() {
    if(!state.gameWon){
      if(state.player.thirst<12)return {label:"脱水终局",icon:"💧",title:"储水耗尽的寒夜",message:"饮水成了最先出现的缺口。下一次要更早建立净水和储水系统。"};
      if(state.player.hunger<12)return {label:"饥饿终局",icon:"🥫",title:"最后一份口粮",message:"食物储备没能支撑漫长寒冬。尝试组合主粮、罐头与种植。"};
      if(state.house.temperature<-10)return {label:"失温终局",icon:"❄️",title:"暖炉熄灭之后",message:"保温与燃料不足以抵御持续降温。升级墙体并为断电准备独立热源。"};
      return {label:"伤病终局",icon:"🩺",title:"没有等到春天",message:state.endReason||"伤病、疲劳或精神压力在极寒中累积，最终压垮了身体。"};
    }
    if(state.house.greenhouse&&(state.counters.harvests||0)>=6)return {label:"温室家园",icon:"🌱",title:"在雪线之上种出春天",message:"你的室内温室成为长期食物来源，安全屋开始自给自足。"};
    if(Object.keys(state.contacts).length>=4)return {label:"互助聚落",icon:"♧",title:"独居者成为一座社区",message:"你与幸存者一起守住了安全屋，互信和分工让人们熬过冬天。"};
    if(state.house.defense>=75)return {label:"钢铁堡垒",icon:"🛡️",title:"风雪也敲不开这扇门",message:"层层加固的安全屋扛过了暴雪、断电和外部威胁。"};
    if((state.counters.explores||0)>=18)return {label:"冰城向导",icon:"⌖",title:"你记住了每一条雪路",message:"一次次外出探索让你建立起自己的地图，也为后来者留下了路线。"};
    if(state.money>=100000)return {label:"物资调度者",icon:"▤",title:"让每一份资源发挥作用",message:"谨慎的资金管理与交易，支撑安全屋走过了整个百日寒冬。"};
    if(equipWarmth()>=26&&state.player.health>=70)return {label:"不屈的守夜人",icon:"🔥",title:"寒夜里始终有人醒着",message:"你守住了温度、健康和日常节奏，在严寒中始终保持清醒。"};
    if(state.house.solar&&state.house.battery)return {label:"离网生活",icon:"☀️",title:"风雪封城，灯火未灭",message:"独立能源让你的安全屋不再依赖摇摇欲坠的城市电网。"};
    if(state.counters.events>=16)return {label:"命运应对者",icon:"✦",title:"没有一场风暴打乱计划",message:"你在一次次突发事件中重新调整安排，最终把意外变成了经验。"};
    if(state.player.sanity>=85)return {label:"安静的冬天",icon:"☾",title:"漫长孤独没有带走你",message:"你用休息、阅读和熟悉的人维持内心秩序，走到了冬天的尽头。"};
    return {label:"综合生存",icon:"❄️",title:"你守住了第 100 天",message:"储备、设施与每一次取舍汇聚成一条活下去的路。"};
  }

  function toast(message) {
    toastRoot.innerHTML='<div class="toast">'+escapeHtml(message)+'</div>';
    window.setTimeout(function(){toastRoot.innerHTML="";},2900);
  }
  function modal(title,body,footer) {
    modalRoot.innerHTML='<div class="modal-backdrop" data-action="dismiss-modal"><section class="modal" role="dialog" aria-modal="true" aria-label="'+escapeHtml(title)+'"><header class="modal-head"><h2>'+escapeHtml(title)+'</h2><button class="modal-close" type="button" data-action="close-modal" aria-label="关闭">×</button></header><div class="modal-body">'+body+'</div>'+(footer?'<footer class="modal-footer">'+footer+'</footer>':"")+'</section></div>';
  }
  function closeModal() { modalRoot.innerHTML=""; }
  function confirmDialog(title,body,confirmAction,confirmLabel) {
    modal(title,'<p class="muted" style="margin:0">'+body+'</p>','<button class="button-secondary" type="button" data-action="close-modal">取消</button><button class="button-primary" type="button" data-action="'+confirmAction+'">'+(confirmLabel||"确认")+'</button>');
  }
  function refresh() { saveState();render(); }
  function finishAction(message) {
    if(message)toast(message);
    ensureAchievements();
    refresh();
  }
  function randomInt(min,max) { return Math.floor(Math.random()*(max-min+1))+min; }

  function startNewGame() {
    var nameInput=document.getElementById("player-name");
    if(nameInput)setup.name=(nameInput.value||"").trim().slice(0,18)||"林见秋";
    state=buildNewState();
    activePage="overview";
    cart=Object.create(null);
    addLog("准备期开始","整理清单、获取收入，并在寒潮前改造住所。你每天有有限的行动时间。","◷");
    saveState();
    render();
    toast("周目开始：距离寒潮还有 30 天。");
  }
  function newRun() {
    deleteSavedState();
    state=null;cart=Object.create(null);
    setup={name:"林见秋",origin:"office",difficulty:"normal",talents:["囤货大师"]};
    activePage="start";render();closeModal();
  }
  function goPage(page) {
    if(!page||!nav.some(function(n){return n[0]===page;}))return;
    activePage=page;closeModal();render();window.scrollTo(0,0);
  }
  function addToCart(id,quantity) {
    var item=getItem(id),qty=Math.floor(Number(quantity)||1),stock=availableStock(currentShop(),item);
    if(qty<1){toast("数量至少为 1。");return;}
    if(stock<=0){toast("这件物资当前已售罄。");return;}
    var existing=cart[id]||0;
    if(existing+qty>stock){qty=stock-existing;}
    if(qty<=0){toast("购物车中数量已达到商家库存。");return;}
    cart[id]=existing+qty;
    render();
    toast("已将 "+item.name+" ×"+qty+" 加入购物车。");
  }
  function checkoutCart() {
    var summary=cartSummary(),shop=currentShop(),ids=Object.keys(cart).filter(function(id){return cart[id]>0;});
    if(!ids.length){toast("购物车还是空的。");return;}
    var delivery=shop.id==="online"||!!window._deliveryMode;
    var freight=shop.id==="online"?0:(delivery?shop.freight:0);
    var total=summary.total+freight;
    if(total>state.money){toast("现金不足，还差 "+money(total-state.money)+"。");return;}
    var stockShort=ids.find(function(id){return cart[id]>availableStock(shop,getItem(id));});
    if(stockShort){toast(getItem(stockShort).name+" 庫存不足，請調整數量。");return;}
    var target=delivery?"warehouse":"pack";
    if(!delivery){
      var load=inventoryLoad("pack");
      var fits=ids.every(function(id){var it=getItem(id),q=cart[id];load.weight+=it.weight*q;load.volume+=it.volume*q;return load.weight<=state.packLimit&&load.volume<=state.packVolume;});
      if(!fits){toast("背包空间或负重不足，请选择配送入库或减少数量。");return;}
    }else if(!storageCanHold(ids[0],0,"warehouse")||summary.weight+inventoryLoad("warehouse").weight+state.orders.reduce(function(a,o){return a+o.weight;},0)>storageCapacity().weight){
      toast("仓库容量不足，先清理或扩建安全屋。");return;
    }
    if(shop.id==="online"&&state.phase==="survival"&&state.survivalDay>0){toast("末日后网上商城已经停运。");return;}
    state.money-=total;
    var lines=ids.map(function(id){var qty=cart[id];state.stock[shop.id][id]-=qty;return {id:id,qty:qty};});
    if(delivery){
      var delay=shop.id==="online"?randomInt(1,3):1;
      if(state.phase==="prep"&&state.day>25)delay+=1;
      state.orders.push({id:"order-"+Date.now(),name:shop.name,lines:lines,weight:summary.weight,volume:summary.volume,daysLeft:delay,paid:total});
      addLog("物资已发运",shop.name+" 的订单预计 "+delay+" 天后送达仓库。","🚚");
    }else{
      lines.forEach(function(line){addInventory("pack",line.id,line.qty);});
      addLog("采购完成","在 "+shop.name+" 购入 "+lines.map(function(l){return getItem(l.id).name+" ×"+l.qty;}).join("、")+"。","🛒");
    }
    state.counters.buys++;state.counters.unitsBought+=summary.count;
    cart=Object.create(null);
    var hours=shop.id==="online"?.5:shop.hours;
    spendHours(hours);
    ensureAchievements();
    refresh();
    toast(delivery?"订单已付款，物流状态已更新。":"已装入随身背包，记得回安全屋入库。");
  }
  function moveItem(id,from) {
    var to=from==="pack"?"warehouse":"pack";
    if(!inventoryCount(from,id)){toast("该位置没有这件物资。");return;}
    if(!storageCanHold(id,1,to)){toast(to==="pack"?"背包负重或空间不足。":"仓库容量不足。");return;}
    removeInventory(from,id,1);addInventory(to,id,1);
    addLog(to==="warehouse"?"物资入库":"装入随身背包",getItem(id).name+" ×1 已转移。","📦");
    finishAction("物资位置已更新。");
  }
  function useItem(id,place) {
    var item=getItem(id),effect=item.effect||{},loc=inventoryCount(place,id)?place:(inventoryCount(place==="pack"?"warehouse":"pack",id)?(place==="pack"?"warehouse":"pack"):null);
    if(!loc){toast("没有这件物资。");return;}
    if(effect.kind==="food"){
      if(effect.raw){toast("这类食材需要先烹饪；打开安全屋查看可用食谱。");return;}
      consumeFood(id,loc);
      addLog("食用补给","食用 "+item.name+"，恢复饱食、水分或精神。","🍽️");
      finishAction("已食用 "+item.name+"。");return;
    }
    if(effect.kind==="drink"){
      removeInventory(loc,id,1);state.player.thirst=clamp(state.player.thirst+effect.thirst,0,100);
      state.player.sanity=clamp(state.player.sanity+(effect.morale||0),0,100);
      addLog("饮用补给","饮用了 "+item.name+"。","🥤");
      finishAction("水分状态已恢复。");return;
    }
    if(effect.kind==="purify"){
      removeInventory(loc,id,1);
      state.house.waterStored=clamp(state.house.waterStored+18,0,state.house.waterCapacity);
      addLog("净化饮水","使用净水片处理原水，增加 "+Math.min(18,state.house.waterCapacity-state.house.waterStored+18)+" L 安全储水。","💧");
      finishAction("原水已处理并存入水箱。");return;
    }
    if(effect.kind==="medicine"){
      removeInventory(loc,id,1);
      var medicalBonus=hasTalent("医生直觉")?1.15:1;
      state.player.health=clamp(state.player.health+Math.round((effect.heal||0)*medicalBonus),0,100);
      state.player.immunity=clamp(state.player.immunity+(effect.immunity||0),0,100);
      state.player.sanity=clamp(state.player.sanity+(effect.morale||0),0,100);
      addLog("使用医疗用品","使用 "+item.name+"，角色状态得到处理。","✚");
      finishAction(item.name+" 已使用。");return;
    }
    if(effect.kind==="wear"){
      removeInventory(loc,id,1);
      state.equipped=state.equipped||{};
      var slot=effect.slot||"outer";
      if(state.equipped[slot])addInventory(loc,state.equipped[slot],1);
      state.equipped[slot]=id;
      addLog("装备保暖衣物","穿上 "+item.name+"，保暖值提升。","🧥");
      finishAction("已装备 "+item.name+"，保暖 +" +effect.warmth+"。");return;
    }
    if(effect.kind==="comfort"){
      removeInventory(loc,id);
      state.player.sanity=clamp(state.player.sanity+(effect.morale||0),0,100);
      if(state.player.stamina<30)state.player.stamina=clamp(state.player.stamina+6,0,100);
      addLog("整理精神状态","使用 "+item.name+" 缓解了压力。","✧");
      finishAction("精神状态得到恢复。");return;
    }
    if(effect.kind==="hygiene"){
      removeInventory(loc,id);
      state.player.cleanliness=clamp(state.player.cleanliness+(effect.clean||0),0,100);
      state.player.immunity=clamp(state.player.immunity+Math.round((effect.clean||0)*.18),0,100);
      addLog("个人清洁","使用 "+item.name+" 清洁身体或住所。","🧼");
      finishAction("清洁度已提升。");return;
    }
    if(effect.kind==="warmth"){
      removeInventory(loc,id);
      state.player.bodyTemp=clamp(state.player.bodyTemp+.5,30,37.2);
      addLog("临时取暖","使用 "+item.name+" 暂时回暖。","♨️");
      finishAction("体温稍有回升。");return;
    }
    if(effect.kind==="seed"){goPage("shelter");toast("种子需要在安全屋温室播种。");return;}
    if(effect.kind==="install"){installItem(id,loc);return;}
    if(effect.kind==="material"){goPage("shelter");toast("材料用于建筑升级或制作。");return;}
    if(effect.kind==="fuel"){toast("燃料会在供暖或发电时自动消耗。");return;}
    if(effect.kind==="tool"){
      toast("工具已生效：提高 "+skillName(effect.skill)+" 技能。");
      return;
    }
    toast("该物品目前没有可执行操作。");
  }
  function consumeFood(id,place) {
    var item=getItem(id),e=item.effect||{};
    removeInventory(place,id,1);
    state.player.hunger=clamp(state.player.hunger+(e.hunger||0),0,100);
    state.player.thirst=clamp(state.player.thirst+(e.thirst||0),0,100);
    state.player.sanity=clamp(state.player.sanity+(e.morale||0),0,100);
    state.player.immunity=clamp(state.player.immunity+(e.nutrition||0),0,100);
  }
  function equipWarmth() {
    var total=hasTalent("耐寒体质")?8:0;
    Object.keys(state.equipped||{}).forEach(function(slot){total+=(getItem(state.equipped[slot]).effect.warmth||0);});
    if(ownsItem("wool-blanket"))total+=1;
    if(ownsItem("sleeping-bag"))total+=2;
    return total;
  }

  function doWork() {
    var today=state.day;
    if(state.phase==="prep"){
      if(state.actions.workDay===today){toast("今天已经工作过了。明天可以再次安排收入。");return;}
      state.actions.workDay=today;
      var pay=Math.round((5200+state.skills.trade*350)*(hasTalent("稳健投资")?1.08:1));
      state.money+=pay;state.player.stamina=clamp(state.player.stamina-24,0,100);
      addLog("完成一班工作","工作 6 小时，获得 "+money(pay)+"。","💼");
      spendHours(6);finishAction("入账 "+money(pay)+"。");
    }else{
      var metal=inventoryTotal("steel"),parts=inventoryTotal("repair-parts");
      if(metal+parts>0){
        var pick=metal>0?"steel":"repair-parts";removeAny(pick,1);
        state.money+=180;state.player.stamina=clamp(state.player.stamina-22,0,100);
        addLog("拆解旧设备","用 4 小时拆出可用零件，在交易点换到少量物资。","🛠️");
        spendHours(4);finishAction("拆解完成，获得 ¥180。");
      }else { toast("末日后的现金难以流通；先探索搜集物资。"); }
    }
  }
  function doRest() {
    if(state.player.stamina>=96&&state.player.sanity>=95){toast("现在状态不错，不需要休息。");return;}
    state.player.stamina=clamp(state.player.stamina+32+(hasTalent("轻度睡眠")?12:0),0,100);
    state.player.sanity=clamp(state.player.sanity+7,0,100);
    if(insideTemperature()<5)state.player.bodyTemp=clamp(state.player.bodyTemp-.15,30,37.2);
    addLog("休息与整理","休息 3 小时，恢复体力并稳定情绪。","☾");
    spendHours(3);finishAction("体力与精神有所恢复。");
  }
  function readNews() {
    var headlines=state.phase==="prep"?[
      ["气象台","北境出现罕见低温带，预计将在本月底南下。"],
      ["城市日报","米、饮用水与取暖设备的搜索量连续攀升。"],
      ["交通广播","东郊货运线路仍然畅通，建议提前安排配送。"]
    ]:[
      ["应急广播","市区多个变电站离线，居民应减少非必要用电。"],
      ["幸存者频道","附近有人用药品和燃料交换主食。"],
      ["气象台","新一轮强风雪正在接近，室外温度继续下降。"]
    ];
    var line=headlines[(state.day+state.log.length)%headlines.length];
    addLog("新闻 · "+line[0],line[1],"📻");
    spendHours(1/6);
    finishAction(line[1]);
  }

  function spendHours(hours) {
    var remaining=Math.max(0,Number(hours)||0),startDay=state.day;
    while(remaining>0&&!state.gameOver&&!state.gameWon){
      var room=22-state.hour;
      if(remaining<room){state.hour+=remaining;remaining=0;}
      else {remaining-=room;state.hour=22;settleDay();if(!state.gameOver&&!state.gameWon){advanceDate();state.hour=8;}}
      if(state.day-startDay>12)break;
    }
    state.hour=clamp(state.hour,8,22);
  }
  function advanceDate() {
    if(state.phase==="prep"&&state.day>=30){
      state.phase="survival";state.day=31;state.survivalDay=1;
      state.event={id:"storm-arrival",title:"超级寒潮抵达",icon:"❄️",text:"暴雪封锁道路，电力将在几天内中断。你的筹备期结束了，接下来要靠储备、设施和判断活下去。",a:"启动安全屋防线",b:"查看最后一次新闻",effect:"arrival",resolved:false};
      state.eventIds.push("storm-arrival");
      addLog("超级寒潮抵达","末日第 1 天。室外气温骤降，物流开始中断。","❄️");
    }else{
      state.day++;
      if(state.phase==="survival")state.survivalDay++;
    }
    if(state.phase==="survival"&&state.survivalDay>=3)state.house.grid=false;
    if(state.phase==="survival"&&state.survivalDay>=100)state.gameWon=true;
  }
  function consumeAutoFood(meals) {
    var candidates=Object.keys(state.inventory.warehouse).concat(Object.keys(state.inventory.pack))
      .filter(function(id){var e=getItem(id).effect;return e.kind==="food"&&!e.raw&&inventoryTotal(id)>0;})
      .sort(function(a,b){return (getItem(b).effect.hunger||0)-(getItem(a).effect.hunger||0);});
    var eaten=0;
    while(eaten<meals&&candidates.length){
      var id=candidates.find(function(x){return inventoryTotal(x)>0;});
      if(!id)break;
      var loc=inventoryCount("warehouse",id)?"warehouse":"pack";
      consumeFood(id,loc);eaten++;
    }
    return eaten;
  }
  function drinkForDay(count) {
    var drank=0;
    while(drank<count){
      if(state.house.waterStored>=1){state.house.waterStored-=1;state.player.thirst=clamp(state.player.thirst+12,0,100);drank++;continue;}
      var ids=["bottled-water","mineral-water","large-water","sports-drink","juice","milk"];
      var id=ids.find(function(x){return inventoryTotal(x)>0;});
      if(!id)break;
      var loc=inventoryCount("warehouse",id)?"warehouse":"pack";
      var item=getItem(id);
      if(item.effect.kind==="food")consumeFood(id,loc);
      else {removeInventory(loc,id,1);state.player.thirst=clamp(state.player.thirst+(item.effect.thirst||25),0,100);}
      drank++;
    }
    return drank;
  }
  function poweredForDay() {
    var h=state.house;
    if(h.grid)return true;
    if(h.solar){
      h.batteryCharge=clamp(h.batteryCharge+(state.day%3===0?0:5),0,30);
      if(h.batteryCharge>0){h.batteryCharge=Math.max(0,h.batteryCharge-2);return true;}
    }
    if(h.wind&&state.day%2===0){h.batteryCharge=clamp(h.batteryCharge+3,0,30);return true;}
    if(h.battery&&inventoryTotal("battery")>0){removeAny("battery",1);h.batteryCharge=clamp(h.batteryCharge+8,0,30);}
    if(h.batteryCharge>0){h.batteryCharge=Math.max(0,h.batteryCharge-2);return true;}
    if(h.generator&&inventoryTotal("diesel")>0){removeAny("diesel",1);return true;}
    if(h.generator&&inventoryTotal("gasoline")>0){removeAny("gasoline",1);return true;}
    return false;
  }
  function settleDay() {
    var oldDay=state.day,oldPhase=state.phase,mode=difficulty(),team=Object.keys(state.contacts).filter(function(id){return state.contacts[id].joined;}).length;
    if(oldPhase==="survival"&&state.survivalDay>=3)state.house.grid=false;
    var powered=poweredForDay();
    if(powered&&state.house.purifier)state.house.waterStored=clamp(state.house.waterStored+(state.house.irrigation?10:8),0,state.house.waterCapacity);
    var stoveFuel=inventoryTotal("firewood")>=3?"firewood":inventoryTotal("coal")>=2?"coal":null;
    var canHeat=state.house.heaterOn&&(state.house.grid || (state.house.generator&&powered) || (state.house.woodStove&&!!stoveFuel));
    if(canHeat&&!state.house.grid&&!powered&&state.house.woodStove&&stoveFuel)removeAny(stoveFuel,stoveFuel==="firewood"?3:2);
    state.house.heatingActive=canHeat;
    if(oldPhase==="prep") {
      state.player.hunger=clamp(state.player.hunger-25*mode.need,0,100);
      state.player.thirst=clamp(state.player.thirst-30*mode.need,0,100);
      var eaten=consumeAutoFood(1+Math.floor(team/2));
      var drank=drinkForDay(1+Math.floor(team/2));
      if(eaten===0)state.player.hunger=clamp(state.player.hunger-12,0,100);
      if(drank===0)state.player.thirst=clamp(state.player.thirst-16,0,100);
    }else{
      var mealNeed=2+Math.ceil(team/2),waterNeed=2+Math.ceil(team/2);
      state.player.hunger=clamp(state.player.hunger-48*mode.need,0,100);
      state.player.thirst=clamp(state.player.thirst-52*mode.need,0,100);
      var meals=consumeAutoFood(mealNeed),water=drinkForDay(waterNeed);
      if(meals<mealNeed)state.player.hunger=clamp(state.player.hunger-(mealNeed-meals)*14,0,100);
      if(water<waterNeed)state.player.thirst=clamp(state.player.thirst-(waterNeed-water)*18,0,100);
    }
    var roomTemp=insideTemperature();
    state.house.temperature=roomTemp;
    var warmth=equipWarmth();
    state.player.bodyTemp=clamp(36.5+clamp((roomTemp+warmth*.72-18)*.04,-7,1),28,37.2);
    if(roomTemp<5)state.player.health=clamp(state.player.health-(5-roomTemp)*.28*mode.risk,0,100);
    if(roomTemp<-12&&warmth<16)state.player.health=clamp(state.player.health-(Math.abs(roomTemp+12)*.5)*mode.risk,0,100);
    if(state.player.hunger<28)state.player.health=clamp(state.player.health-4*mode.risk,0,100);
    if(state.player.thirst<25)state.player.health=clamp(state.player.health-6*mode.risk,0,100);
    if(state.player.cleanliness<24)state.player.immunity=clamp(state.player.immunity-6,0,100);
    if(!state.house.ventilation)state.player.cleanliness=clamp(state.player.cleanliness-(oldPhase==="survival"?3:1),0,100);
    if(state.player.immunity<25)state.player.health=clamp(state.player.health-3,0,100);
    state.player.stamina=clamp(state.player.stamina+30+(hasTalent("轻度睡眠")?12:0),0,100);
    if(state.player.sanity<50&&!hasTalent("钢铁意志"))state.player.health=clamp(state.player.health-1,0,100);
    if(oldPhase==="survival"&&!hasTalent("钢铁意志"))state.player.sanity=clamp(state.player.sanity-2,0,100);
    spoilFood(powered);
    updateOrders();
    growPlants(powered);
    refreshStock();
    state.actions.studyDay=0;
    state.counters.days=(state.counters.days||0)+1;
    addLog("第 "+oldDay+" 天结束","室外 "+(oldPhase==="prep"?Math.round(17-(oldDay-1)*.83):Math.max(-76,Math.round(-35-(state.survivalDay-1)*.68)))+"°C · 室内 "+roomTemp+"°C · "+(powered?"供电可用":"供电中断")+" · 饱食 "+Math.round(state.player.hunger)+" · 水分 "+Math.round(state.player.thirst),"☾");
    ensureAchievements();
    maybeEvent();
    if(state.player.health<=0){state.gameOver=true;state.endReason=state.player.thirst<15?"脱水与寒冷耗尽了生命。":state.player.hunger<15?"饥饿与寒冷耗尽了生命。":"伤病和极寒超过了身体承受能力。";}
    if(oldPhase==="survival"&&state.survivalDay>=100)state.gameWon=true;
  }
  function spoilFood(powered) {
    Object.keys(state.age||{}).forEach(function(id){
      var item=getItem(id),qty=inventoryTotal(id);
      if(!qty||item.shelf>=90)return;
      var preservation=state.house.fridge||state.house.freezer||state.house.coolbox;
      state.age[id]=(state.age[id]||0)+(preservation&&powered?.25:1);
      while(state.age[id]>=item.shelf&&inventoryTotal(id)>0){
        removeAny(id,1);state.age[id]-=item.shelf;
        addLog("食物腐坏",item.name+" 因保鲜条件不足而过期，已丢弃一份。","⚠️");
      }
    });
  }
  function updateOrders() {
    state.orders=state.orders.filter(function(order){
      order.daysLeft--;
      if(order.daysLeft>0)return true;
      var total=order.lines.reduce(function(a,l){return a+l.qty;},0);
      var cap=storageCapacity(),load=inventoryLoad("warehouse");
      var pendingWeight=state.orders.reduce(function(a,o){return a+o.weight;},0);
      var pendingVolume=state.orders.reduce(function(a,o){return a+(o.volume||0);},0);
      var fits=load.weight+pendingWeight<=cap.weight&&load.volume+pendingVolume<=cap.volume;
      if(!fits){order.daysLeft=1;addLog("配送等待入库空间",order.name+" 已到达，但仓库容量不足。","📦");return true;}
      order.lines.forEach(function(line){addInventory("warehouse",line.id,line.qty);});
      addLog("配送送达","来自 "+order.name+" 的 "+total+" 件物资已入库。","🚚");
      return false;
    });
  }
  function growPlants(powered) {
    (state.plots||[]).forEach(function(plot){
      if(plot.daysLeft>0)plot.daysLeft-=powered&&state.house.growLight?2:1;
    });
  }
  function maybeEvent() {
    if(state.event&&!state.event.resolved)return;
    if(state.phase==="prep"&&state.day<5)return;
    if((state.day+state.counters.events)%4!==0)return;
    var index=hashText(state.day+"|"+state.survivalDay+"|"+state.name)%eventPool.length;
    var base=eventPool[index];
    state.event={id:"event-"+state.phase+"-"+state.day+"-"+index,title:base.title,icon:base.icon,text:base.text,a:base.a,b:base.b,effect:base.effect,resolved:false};
    state.eventIds.push(state.event.id);
  }
  function ensureAchievements() {
    if(!state)return;
    achievements.forEach(function(a){if(state.achievements.indexOf(a.id)<0&&a.check(state)){state.achievements.push(a.id);addLog("成就解锁",a.name+"： "+a.desc,"🏅");}});
  }

  function buildUpgrade(id) {
    var upgrade=upgrades.find(function(u){return u.id===id;});
    if(!upgrade){toast("找不到这项改造。");return;}
    if(hasUpgrade(id)){toast("这项设施已经安装。");return;}
    if(state.money<upgrade.cost){toast("资金不足，还需要 "+money(upgrade.cost-state.money)+"。");return;}
    if(!hasMaterials(upgrade.materials)){toast("材料不足：还需要 "+materialLabel(missingMaterials(upgrade.materials))+"。");return;}
    var cost=Object.create(null);
    Object.keys(upgrade.materials).forEach(function(itemId){cost[itemId]=upgrade.materials[itemId];});
    if(hasTalent("工匠世家"))Object.keys(cost).forEach(function(itemId){cost[itemId]=Math.max(1,Math.ceil(cost[itemId]*.9));});
    if(!hasMaterials(cost)){toast("工匠优惠后仍有材料不足。");return;}
    state.money-=upgrade.cost;
    Object.keys(cost).forEach(function(itemId){removeAny(itemId,cost[itemId]);});
    upgrade.apply(state);
    state.house.upgrades.push(id);
    addLog("安全屋改造",upgrade.name+" 已完成："+upgrade.desc,"🛠️");
    spendHours(upgrade.hours*(hasTalent("心灵手巧")?.8:1));
    finishAction(upgrade.name+" 完工，设施属性已更新。");
  }
  function missingMaterials(materials) {
    var missing=Object.create(null);
    Object.keys(materials||{}).forEach(function(id){var lack=(materials[id]||0)-inventoryTotal(id);if(lack>0)missing[id]=lack;});
    return missing;
  }
  function installItem(id,place) {
    var item=getItem(id),effect=item.effect||{},up=upgrades.find(function(u){return u.id===effect.upgrade;});
    if(up&&hasUpgrade(up.id)){toast(up.name+" 已安装，物资仍保留。");return;}
    if(!removeInventory(place,id,1)){toast("设备不在当前库存。");return;}
    if(up){
      up.apply(state);state.house.upgrades.push(up.id);
      addLog("安装应急设备",item.name+" 已安装，"+up.desc,"🔌");
    }else{
      state.house.installed=state.house.installed||{};
      state.house.installed[effect.upgrade]=true;
      if(effect.upgrade==="heater"){state.house.heaterBonus=6;state.house.heaterOn=true;}
      if(effect.upgrade==="blanket")state.house.nightHeat=8;
      if(effect.upgrade==="cooker")state.house.cooker=true;
      if(effect.upgrade==="growlight")state.house.growLight=true;
      if(effect.upgrade==="irrigation")state.house.irrigation=true;
      if(effect.upgrade==="radio")state.house.radio=true;
      if(effect.upgrade==="toilet")state.house.toilet=true;
      if(effect.upgrade==="pump")state.house.pump=true;
      if(effect.upgrade==="coolbox")state.house.coolbox=true;
      if(effect.upgrade==="lantern")state.house.lantern=true;
      if(effect.upgrade==="freezer")state.house.freezer=true;
      if(effect.upgrade==="lamp")state.house.lamp=true;
      if(effect.upgrade==="wind")state.house.wind=true;
      addLog("安装设备",item.name+" 已并入安全屋设施。","🔌");
    }
    spendHours(1.5);
    finishAction(item.name+" 已安装并投入使用。");
  }
  function cookMeal(id) {
    var recipe=recipes.find(function(r){return r.id===id;});
    if(!recipe){toast("食谱不存在。");return;}
    if(!hasMaterials(recipe.need)){toast("食材不足：还需要 "+materialLabel(missingMaterials(recipe.need))+"。");return;}
    var cookingPower=state.house.grid||inventoryTotal("gas-canister")>0||state.house.woodStove&&inventoryTotal("firewood")>0;
    if(!cookingPower){toast("厨房没有电力或燃料，暂时无法烹饪。");return;}
    Object.keys(recipe.need).forEach(function(itemId){removeAny(itemId,recipe.need[itemId]);});
    addInventory("warehouse",recipe.result,1);
    state.skills.cooking=clamp((state.skills.cooking||1)+.12,1,10);
    state.counters.crafts++;
    addLog("完成烹饪",recipe.name+" 已放入食品储藏。","🍲");
    spendHours(recipe.hours*(hasTalent("心灵手巧")?.85:1));
    finishAction(recipe.name+" 做好了。");
  }
  function plantCrop() {
    if(!state.house.greenhouse){toast("先建造室内温室。");return;}
    if(state.plots.length>=2){toast("两个种植位都已使用。");return;}
    var seedIds=["potato-seeds","tomato-seeds","cabbage-seeds","carrot-seeds","corn-seeds","soy-seeds","onion-seeds","radish-seeds"];
    var owned=seedIds.filter(function(id){return inventoryTotal(id)>0;});
    if(!owned.length){toast("没有种子；可以在农贸市场购买。");return;}
    if(inventoryTotal("potting-soil")<1){toast("播种需要一份营养培养土。");return;}
    var choices=owned.map(function(id){var e=getItem(id).effect;return '<button class="choice-card" type="button" data-action="plant-seed" data-id="'+id+'"><strong>'+getItem(id).icon+" "+escapeHtml(getItem(id).name)+'</strong><small>'+escapeHtml(getItem(e.crop).name)+' · '+e.days+' 天 · 预计 '+e.yield+' 份</small></button>';}).join("");
    modal("选择种子",'<p class="muted" style="font-size:11px;margin-top:0">消耗 1 份种子与 1 袋培养土，作物在温室中按天生长。</p><div class="choice-grid">'+choices+'</div>');
  }
  function plantSeed(seedId) {
    var item=getItem(seedId),e=item.effect;
    if(inventoryTotal(seedId)<1||inventoryTotal("potting-soil")<1){closeModal();toast("种植材料不足。");return;}
    if(state.plots.length>=2){closeModal();toast("种植位已满。");return;}
    removeAny(seedId,1);removeAny("potting-soil",1);
    state.plots.push({seed:seedId,crop:e.crop,daysLeft:e.days,yield:Math.round(e.yield*(hasTalent("园艺高手")?1.25:1))});
    state.skills.farming=clamp((state.skills.farming||1)+.15,1,10);
    addLog("温室播种","种下 "+item.name+"，预计 "+e.days+" 天后收获。","🌱");
    closeModal();spendHours(1.5);finishAction("种子已播下，温室开始生长。");
  }
  function harvest(index) {
    var plot=state.plots[index];
    if(!plot||plot.daysLeft>0){toast("作物还没有成熟。");return;}
    if(!storageCanHold(plot.crop,plot.yield,"warehouse")){toast("仓库空间不足，先整理物资再收获。");return;}
    addInventory("warehouse",plot.crop,plot.yield);state.plots.splice(index,1);state.counters.harvests++;
    addLog("温室收获","收获 "+getItem(plot.crop).name+" ×"+plot.yield+"。","🌾");
    finishAction("收获已入库。");
  }
  function explore(id) {
    var loc=locations.find(function(x){return x.id===id;});
    if(!loc){toast("地点不存在。");return;}
    if(state.player.stamina<22){toast("体力不足，先休息后再外出。");return;}
    var mode=difficulty();
    var warmth=equipWarmth();
    var risk=loc.risk*mode.risk;
    risk-=Math.max(0,(state.skills.exploration||1)-1)*2.5;
    risk-=warmth>8?4:0;
    risk-=hasTalent("生存专家")?6:0;
    risk-=hasTalent("野外耳目")?7:0;
    risk-=ownsItem("flashlight")?2:0;
    if(state.phase==="survival")risk+=12;
    risk=clamp(risk,5,78);
    if(state.settings.confirmActions&&risk>=30){
      confirmDialog("高风险探索",loc.name+" 的基础风险约 "+Math.round(risk)+"%。本次出行需要 "+loc.hours+" 小时，并会消耗体力。要继续吗？","confirm-explore-"+id,"继续出发");
      return;
    }
    performExplore(loc,risk);
  }
  function performExplore(loc,risk) {
    var mode=difficulty();
    var cost=12+loc.hours*5;
    if(hasTalent("耐力惊人"))cost*=.85;
    state.player.stamina=clamp(state.player.stamina-cost,0,100);
    if(state.phase==="survival"){
      state.player.thirst=clamp(state.player.thirst-(ownsItem("water-canteen")?4:8),0,100);
      state.player.bodyTemp=clamp(state.player.bodyTemp-.2,28,37.2);
    }
    var safe=Math.random()*100>=risk;
    var gained=[];
    if(safe){
      var amount=2+(hasTalent("生存专家")?1:0)+(hasTalent("幸运儿")&&Math.random()<.3?1:0);
      for(var i=0;i<amount;i++){
        var id=loc.loot[randomInt(0,loc.loot.length-1)],item=getItem(id);
        var qty=item.category==="主食"||item.category==="蔬果"||item.category==="饮水"?randomInt(1,2):1;
        if(storageCanHold(id,qty,"pack")){addInventory("pack",id,qty);gained.push(item.name+" ×"+qty);}
      }
      state.counters.explores++;
      state.skills.exploration=clamp((state.skills.exploration||1)+.18,1,10);
      state.journal.push({type:"location",id:loc.id});
      state.journal=state.journal.slice(-60);
      addLog("探索成功 · "+loc.name,gained.length?"找到 "+gained.join("、")+"，记得在背包页入库。":"找到的物资太重，只能丢下。","⌖");
      state.player.sanity=clamp(state.player.sanity+2,0,100);
    }else{
      var injury=randomInt(7,18)*mode.risk;
      state.player.health=clamp(state.player.health-injury,0,100);
      state.player.immunity=clamp(state.player.immunity-4,0,100);
      if(Math.random()<.3&&inventoryTotal("bandage")>0)removeAny("bandage",1);
      addLog("探索受挫 · "+loc.name,"遭遇危险，受伤 "+Math.round(injury)+" 点生命，没有带回补给。","⚠️");
    }
    if(state.phase==="survival"&&outsideTemperature()<-45&&equipWarmth()<12)state.player.health=clamp(state.player.health-6,0,100);
    spendHours(loc.hours);
    if(state.player.health<=0){state.gameOver=true;state.endReason="外出探索中的伤势没有得到及时处理。";}
    finishAction(safe?(gained.length?"找到 "+gained.join("、"):"勉强安全返回"):"遭遇危险，尽快治疗伤势。");
  }
  function meetNpc(id) {
    var npc=npcs.find(function(x){return x.id===id;});
    if(!npc)return;
    if(state.player.stamina<8){toast("体力不足，休息后再拜访。");return;}
    if(state.contacts[id]){toast("你们已经认识了。");return;}
    state.contacts[id]={met:true,joined:false,affinity:hasTalent("社区熟面孔")?28:18,metDay:state.day};
    state.player.stamina=clamp(state.player.stamina-6,0,100);
    addLog("建立新关系",npc.name+"（"+npc.role+"）愿意互相留意消息。","♧");
    spendHours(1);finishAction("已与 "+npc.name+" 建立联系。");
  }
  function talkNpc(id) {
    var npc=npcs.find(function(x){return x.id===id;}),contact=state.contacts[id];
    if(!npc||!contact)return;
    if(contact.lastTalk===state.day){toast("今天已经拜访过 "+npc.name+"。");return;}
    contact.lastTalk=state.day;contact.affinity=clamp(contact.affinity+12+(hasTalent("社区熟面孔")?5:0),0,100);
    state.player.sanity=clamp(state.player.sanity+5,0,100);
    if(npc.skill==="medical"&&inventoryTotal("bandage")>0){removeAny("bandage",1);state.player.health=clamp(state.player.health+8,0,100);}
    addLog("拜访幸存者",npc.name+" 分享了附近情况，彼此信任提高。","♧");
    spendHours(1);finishAction(npc.name+" 的信任提高到 "+contact.affinity+"。");
  }
  function inviteNpc(id) {
    var npc=npcs.find(function(x){return x.id===id;}),contact=state.contacts[id];
    if(!npc||!contact)return;
    if(contact.joined){toast(npc.name+" 已经同行。");return;}
    if(state.phase==="survival"&&contact.affinity<35){toast("信任度至少达到 35 后，对方才愿意同行。");return;}
    contact.joined=true;contact.affinity=clamp(contact.affinity+6,0,100);
    if(npc.skill==="medical")state.player.immunity=clamp(state.player.immunity+8,0,100);
    addLog("幸存者加入",npc.name+" 加入团队。团队每日需要更多口粮和饮水。","♧");
    spendHours(1);finishAction(npc.name+" 加入了安全屋团队。");
  }
  function resolveEvent(choice) {
    var ev=state.event;
    if(!ev||ev.resolved){toast("这个事件已经处理过。");return;}
    ev.resolved=true;
    var answer=choice==="a";
    switch(ev.effect){
      case "arrival":
        if(answer){state.house.defense+=5;state.player.sanity=clamp(state.player.sanity+3,0,100);}
        else {addLog("最后一条广播","你记下了电网和道路的最新状况。","📻");}
        break;
      case "fuel":
        if(answer){if(state.money>=1200){state.money-=1200;addInventory("warehouse","firewood",8);}else state.player.sanity=clamp(state.player.sanity-4,0,100);}
        else state.money+=2400;
        break;
      case "trade":
        if(answer){if(inventoryTotal("wool-blanket")>0)removeAny("wool-blanket",1);else if(inventoryTotal("instant-noodles")>0)removeAny("instant-noodles",2);addInventory("warehouse","bottled-water",4);state.player.sanity=clamp(state.player.sanity+5,0,100);}
        else state.player.sanity=clamp(state.player.sanity-1,0,100);
        break;
      case "sale":
        if(answer){if(state.money>=1200){state.money-=1200;addInventory("warehouse","emergency-ration",4);addInventory("warehouse","bottled-water",4);}else toast("资金不足，清仓机会错过了。");}
        break;
      case "power":
        if(answer){addInventory("warehouse","battery",4);state.house.defense+=1;}
        else state.player.sanity=clamp(state.player.sanity-2,0,100);
        break;
      case "rescue":
        if(answer){
          if(state.player.stamina>=8){state.player.stamina-=8;if(inventoryTotal("bottled-water")>0)removeAny("bottled-water",1);else state.house.waterStored=Math.max(0,state.house.waterStored-1);state.player.sanity=clamp(state.player.sanity+8,0,100);state.contacts.an=state.contacts.an||{met:true,joined:false,affinity:24};}
          else {state.player.sanity=clamp(state.player.sanity-2,0,100);}
          spendHours(1);
        }else state.player.sanity=clamp(state.player.sanity-2,0,100);
        break;
      case "shipping":
        if(answer&&state.money>=300){state.money-=300;state.orders.forEach(function(o){o.daysLeft=Math.max(1,o.daysLeft-1);});}
        break;
      case "repair":
        if(answer&&inventoryTotal("duct-tape")>0){removeAny("duct-tape",1);state.house.insulation=clamp(state.house.insulation+3,0,95);}
        else if(answer&&inventoryTotal("waterproof-seal")>0){removeAny("waterproof-seal",1);state.house.insulation=clamp(state.house.insulation+2,0,95);}
        else if(answer)state.player.health=clamp(state.player.health-2,0,100);
        break;
      case "seed":
        if(answer&&state.money>=680){state.money-=680;addInventory("warehouse","potato-seeds",2);addInventory("warehouse","potting-soil",1);}
        break;
      case "defense":
        if(answer){state.house.defense+=4;if(inventoryTotal("door-lock")>0)removeAny("door-lock",1);}
        else {state.player.sanity=clamp(state.player.sanity+1,0,100);}
        break;
      case "hope":
        state.player.sanity=clamp(state.player.sanity+(answer?9:5),0,100);
        state.journal.push({type:"location",id:"observatory"});
        break;
      case "prices":
        if(answer&&state.money>=280){state.money-=280;addInventory("warehouse","rice",12);}
        else state.marketQuality=.72;
        break;
      case "community":
        if(answer&&inventoryTotal("wool-blanket")>0){removeAny("wool-blanket",1);state.contacts.mei=state.contacts.mei||{met:true,joined:false,affinity:32};state.player.sanity=clamp(state.player.sanity+6,0,100);}
        else if(answer)state.player.sanity=clamp(state.player.sanity+2,0,100);
        break;
    }
    state.counters.events++;addLog("事件处理 · "+ev.title,answer?ev.a:ev.b,"✦");
    spendHours(ev.effect==="arrival"?.25:.2);
    finishAction("选择已记录，事件后果已生效。");
  }

  function exportSave() {
    saveState();
    var blob=new Blob([JSON.stringify(state,null,2)],{type:"application/json"});
    var url=URL.createObjectURL(blob),a=document.createElement("a");
    a.href=url;a.download="starlit-frostfall-save-day-"+state.day+".json";a.click();
    window.setTimeout(function(){URL.revokeObjectURL(url);},1000);
    toast("存档文件已导出。");
  }
  function importSaveFile(file) {
    if(!file)return;
    var reader=new FileReader();
    reader.onload=function(){
      try{state=parseSaveFile(String(reader.result||""));activePage="overview";saveState();render();toast("存档已导入并自动保存。");}
      catch(error){toast(error.message||"无法读取存档文件。");}
    };
    reader.readAsText(file);
  }
  function startNewConfirm() {
    confirmDialog("重新开始本周目","新周目会覆盖当前保存进度。建议先导出存档备份。","confirm-new-run","确认新周目");
  }
  function updateSearch(id,value) {
    if(id==="market-search"){marketSearch=value;var result=document.getElementById("market-results");if(result)result.innerHTML=renderMarketCards();}
    if(id==="warehouse-search"){warehouseSearch=value;var start=window.getSelection?window.getSelection().toString():"";render();var input=document.getElementById("warehouse-search");if(input){input.focus();input.setSelectionRange(value.length,value.length);}}
  }

  document.addEventListener("click",function(event){
    var target=event.target.closest("[data-action]");
    if(!target)return;
    var action=target.getAttribute("data-action"),id=target.getAttribute("data-id"),page=target.getAttribute("data-page");
    if(action==="dismiss-modal"&&event.target.classList.contains("modal-backdrop")){closeModal();return;}
    if(action==="close-modal"){closeModal();return;}
    if(action==="select-origin"){setup.origin=id;renderStart();return;}
    if(action==="select-difficulty"){setup.difficulty=id;renderStart();return;}
    if(action==="toggle-talent"){
      var talentId=id,at=setup.talents.indexOf(talentId);
      if(at>=0)setup.talents.splice(at,1);else if(setup.talents.length<3)setup.talents.push(talentId);else{toast("每局最多选择 3 项天赋。");return;}
      renderStart();return;
    }
    if(action==="start-game"){startNewGame();return;}
    if(action==="continue-game"){if(state){activePage="overview";render();}return;}
    if(action==="navigate"){goPage(page);return;}
    if(action==="mobile-more"){
      modal("更多页面",'<div class="choice-grid"><button type="button" class="choice-card" data-action="navigate" data-page="people"><strong>♧ 幸存者</strong><small>建立联系、交流并邀请同行</small></button><button type="button" class="choice-card" data-action="navigate" data-page="journal"><strong>▧ 日志与目标</strong><small>任务、成就与行动记录</small></button><button type="button" class="choice-card" data-action="navigate" data-page="settings"><strong>⚙ 设置与存档</strong><small>导入、导出与重新开始</small></button></div>');return;
    }
    if(action==="select-shop"){shopId=id;cart=Object.create(null);render();return;}
    if(action==="add-cart"){
      var quantityInput=document.getElementById("qty-"+id);
      addToCart(id,quantityInput?quantityInput.value:1);return;
    }
    if(action==="checkout"){checkoutCart();return;}
    if(action==="move-item"){moveItem(id,target.getAttribute("data-from"));return;}
    if(action==="use-item"){useItem(id,target.getAttribute("data-place")||"warehouse");return;}
    if(action==="sell-item"){sellItem(id,target.getAttribute("data-place")||"warehouse");return;}
    if(action==="work"){doWork();return;}
    if(action==="rest"){doRest();return;}
    if(action==="news"){readNews();return;}
    if(action==="end-day"){
      if(state.settings.confirmActions)confirmDialog("结束今天","将结算食物、饮水、温度、能源、作物、物价和订单；行动时间会切换到明天。","confirm-end-day","结束当天");
      else endDayNow();
      return;
    }
    if(action==="confirm-end-day"){closeModal();endDayNow();return;}
    if(action==="build-upgrade"){buildUpgrade(id);return;}
    if(action==="toggle-heater"){state.house.heaterOn=!state.house.heaterOn;state.house.heatingActive=state.house.heaterOn&&(state.house.grid||(state.house.generator&&inventoryTotal("diesel")>0)||(state.house.woodStove&&inventoryTotal("firewood")>=3));addLog("供暖调整",state.house.heaterOn?"手动开启供暖。":"手动关闭供暖。","♨️");finishAction(state.house.heaterOn?"供暖已开启。":"供暖已关闭。");return;}
    if(action==="toggle-device"){
      var key=id;state.house[key]=!state.house[key];
      if(key==="fridge"&&state.house[key])state.house.freezer=true;
      addLog("设备调整",key+" 已"+(state.house[key]?"开启":"关闭")+"。","🔌");finishAction("安全屋设备状态已更新。");return;
    }
    if(action==="cook"){cookMeal(id);return;}
    if(action==="plant"){plantCrop();return;}
    if(action==="plant-seed"){plantSeed(id);return;}
    if(action==="harvest"){harvest(Number(target.getAttribute("data-index")));return;}
    if(action==="explore"){explore(id);return;}
    if(action.indexOf("confirm-explore-")===0){
      closeModal();var locId=action.slice("confirm-explore-".length),loc=locations.find(function(x){return x.id===locId;});
      if(loc){var risk=loc.risk*difficulty().risk-Math.max(0,(state.skills.exploration||1)-1)*2.5-(equipWarmth()>8?4:0)-(hasTalent("野外耳目")?7:0);performExplore(loc,clamp(risk,5,78));}
      return;
    }
    if(action==="meet-npc"){meetNpc(id);return;}
    if(action==="talk"){talkNpc(id);return;}
    if(action==="invite"){inviteNpc(id);return;}
    if(action==="event-choice"){resolveEvent(id);return;}
    if(action==="save-now"){saveState();toast("游戏状态已保存到 "+saveBackend+"。");return;}
    if(action==="export-save"){exportSave();return;}
    if(action==="import-save"){var fileInput=document.getElementById("save-file-input");if(fileInput)fileInput.click();return;}
    if(action==="reset-save"){startNewConfirm();return;}
    if(action==="confirm-new-run"){newRun();return;}
    if(action==="new-run"){startNewConfirm();return;}
    if(action==="setting-toggle"){
      state.settings[id]=!state.settings[id];refresh();toast(id==="animations"?"动效设置已更新。":"行动确认设置已更新。");return;
    }
    if(action==="font-size"){
      state.settings.textScale=state.settings.textScale>=120?90:state.settings.textScale+10;
      document.documentElement.style.fontSize=state.settings.textScale+"%";refresh();toast("界面字号："+state.settings.textScale+"%。");return;
    }
    if(action==="dismiss-event"){state.event.resolved=true;finishAction("事件已暂时搁置。");return;}
    if(action==="new-quick"){startNewConfirm();return;}
  });

  document.addEventListener("input",function(event){
    var el=event.target;
    if(el.id==="player-name"){setup.name=el.value;return;}
    if(el.id==="market-search"){updateSearch(el.id,el.value);return;}
    if(el.id==="warehouse-search"){updateSearch(el.id,el.value);return;}
  });
  document.addEventListener("change",function(event){
    var el=event.target;
    if(el.id==="market-category"){marketCategory=el.value;render();return;}
    if(el.id==="market-sort"){marketSort=el.value;render();return;}
    if(el.id==="delivery-mode"){window._deliveryMode=el.checked;render();return;}
    if(el.id==="save-file-input"&&el.files&&el.files.length){importSaveFile(el.files[0]);el.value="";}
  });

  function endDayNow() {
    closeModal();
    settleDay();
    if(!state.gameOver&&!state.gameWon){advanceDate();state.hour=8;}
    if(state.gameOver)toast("本周目结束。");
    else if(state.gameWon)toast("百日生存目标完成。");
    else toast("进入第 "+state.day+" 天。");
    refresh();
  }
  function sellItem(id,place) {
    if(state.phase!=="prep"){toast("末日后货币交易已停止。");return;}
    var amount=inventoryCount(place,id);
    if(amount<=0){toast("没有可出售的物资。");return;}
    var count=1,item=getItem(id),factor=.43+(state.skills.trade||1)*.025;
    if(hasTalent("天生商人"))factor*=1.18;
    var value=Math.max(1,Math.round(item.price*factor));
    removeInventory(place,id,count);state.money+=value;
    addLog("出售闲置物品","出售 "+item.name+" ×"+count+"，获得 "+money(value)+"。","↗");
    spendHours(.5);finishAction("出售完成，收入 "+money(value)+"。");
  }

  // Init handles current local browser and Starlit WebView storage environments.
  if(state){
    state.inventory=state.inventory||{warehouse:{},pack:{}};
    state.inventory.warehouse=state.inventory.warehouse||{};
    state.inventory.pack=state.inventory.pack||{};
    state.settings=state.settings||{confirmActions:true,animations:true,textScale:100};
    state.equipped=state.equipped||{};
    state.orders=state.orders||[];state.plots=state.plots||[];state.contacts=state.contacts||{};
    state.house.installed=state.house.installed||{};
    state.house.heatingActive=state.house.heatingActive==null?null:state.house.heatingActive;
    if(state.stock==null)state.stock=initStock();
    if(state.gameOver==null)state.gameOver=false;
    if(state.gameWon==null)state.gameWon=false;
  }
  document.body.classList.toggle("no-animations",!!(state&&state.settings&&state.settings.animations===false));
  if (window.addEventListener) {
    window.addEventListener("beforeunload",saveState);
  }

  function selfTestPrimaryPurchaseFlow() {
    var previousState=state,previousPage=activePage,previousShop=shopId,previousCart=cart,passed=false;
    try {
      state=buildNewState();
      activePage="market";
      shopId="supermarket";
      cart=Object.create(null);
      var item=items.find(function(candidate){return candidate.shops.indexOf(shopId)>=0&&availableStock(currentShop(),candidate)>0;});
      if(item){addToCart(item.id,1);passed=cart[item.id]===1;}
    } catch (error) {
      passed=false;
    } finally {
      state=previousState;activePage=previousPage;shopId=previousShop;cart=previousCart;
      if(toastRoot)toastRoot.innerHTML="";
      render();
    }
    return passed;
  }

  window.__STARLIT_SELF_TEST__ = function () {
    var results=[
      {name:"project-state-version",passed:!state||state.version===1,message:"游戏状态版本无效"},
      {name:"market-catalog",passed:items.length>=150,message:"物资目录未达到项目规格要求"},
      {name:"save-key-stable",passed:STORAGE_KEY==="frostfall-game-v1",message:"存档 key 与项目规格不一致"},
      {name:"initial-state-route",passed:!!document.querySelector('[data-testid="start-game"]')||!!document.querySelector('[data-testid="phase-countdown"]'),message:"角色创建或游戏主界面未呈现"},
      {name:"primary-purchase-flow",passed:selfTestPrimaryPurchaseFlow(),message:"无法把物资加入采购购物车"}
    ];
    return results;
  };

  render();
})();

