package com.qwenpaint

/**
 * 中文提示词 → 英文 tag。
 *
 * SD-Turbo 的文本编码器是 OpenAI CLIP ViT-L/14，tokenizer 只认英文 BPE：中文进去会被切成
 * byte-level token，CLIP 完全读不懂，出图基本是噪声。这不是 App 的 bug，是模型架构决定的。
 *
 * 不接在线翻译（违背离线定位，容器里网络也不稳，还会把提示词传出去），改用内置词表：
 * SD 的 prompt 本来就是一堆 tag 堆起来的，按最长匹配把中文切词替换成英文 tag，CLIP 就能吃。
 * 未收录的词直接丢弃，不硬塞乱码。
 */
object ZhPrompt {

    /** 虚词/量词，先剔掉，避免干扰切词 */
    private val STOP_WORDS = listOf(
        "一个", "一只", "一位", "一条", "一张", "一幅", "一些", "一片", "一群",
        "的", "了", "着", "在", "上", "里", "中", "和", "与", "及", "或",
        "非常", "超级", "特别", "十分", "很", "有点", "正在", "然后", "而且", "并且",
    )

    private val DICT: Map<String, String> = mapOf(
        // ---------------- 人物 ----------------
        "女孩" to "a girl", "女生" to "a girl", "少女" to "a young girl",
        "女人" to "a woman", "女士" to "a lady", "男子" to "a man", "男人" to "a man",
        "男孩" to "a boy", "少年" to "a boy", "儿童" to "a child", "宝宝" to "a baby",
        "老人" to "an elderly person", "情侣" to "a couple", "新娘" to "a bride",
        "公主" to "a princess", "王子" to "a prince", "女王" to "a queen", "国王" to "a king",
        "武士" to "a samurai", "骑士" to "a knight", "忍者" to "a ninja", "海盗" to "a pirate",
        "宇航员" to "an astronaut", "机器人" to "a robot", "女巫" to "a witch", "法师" to "a mage",
        "天使" to "an angel", "恶魔" to "a demon", "精灵" to "an elf", "吸血鬼" to "a vampire",
        "医生" to "a doctor", "厨师" to "a chef", "舞者" to "a dancer", "士兵" to "a soldier",
        "科学家" to "a scientist", "侦探" to "a detective", "农夫" to "a farmer", "画家" to "a painter",
        "音乐家" to "a musician", "模特" to "a fashion model", "背包客" to "a backpacker",
        // ---------------- 动物 ----------------
        "猫" to "a cat", "猫咪" to "a cat", "小猫" to "a kitten", "橘猫" to "an orange tabby cat",
        "狗" to "a dog", "小狗" to "a puppy", "柯基" to "a corgi", "柴犬" to "a shiba inu",
        "金毛" to "a golden retriever", "哈士奇" to "a husky", "狼" to "a wolf", "狐狸" to "a fox",
        "老虎" to "a tiger", "狮子" to "a lion", "熊猫" to "a panda", "兔子" to "a rabbit",
        "仓鼠" to "a hamster", "松鼠" to "a squirrel", "鹿" to "a deer", "马" to "a horse",
        "大象" to "an elephant", "猴子" to "a monkey", "企鹅" to "a penguin", "海豹" to "a seal",
        "鸟" to "a bird", "猫头鹰" to "an owl", "鹰" to "an eagle", "天鹅" to "a swan",
        "龙" to "a dragon", "恐龙" to "a dinosaur", "鲸鱼" to "a whale", "鲨鱼" to "a shark",
        "海豚" to "a dolphin", "蝴蝶" to "a butterfly", "蜜蜂" to "a bee", "蜻蜓" to "a dragonfly",
        "青蛙" to "a frog", "蛇" to "a snake", "蜘蛛" to "a spider", "章鱼" to "an octopus",
        "水母" to "a jellyfish", "独角兽" to "a unicorn", "凤凰" to "a phoenix",
        // ---------------- 场景 / 地点 ----------------
        "海边" to "at the beach", "沙滩" to "on a sandy beach", "海洋" to "the ocean",
        "大海" to "the sea", "海浪" to "ocean waves", "森林" to "in a forest", "树林" to "in the woods",
        "山顶" to "on a mountain peak", "山脉" to "mountains", "雪山" to "snowy mountains",
        "山谷" to "a valley", "悬崖" to "a cliff", "沙漠" to "a desert", "草原" to "a grassland",
        "田野" to "a field", "湖泊" to "a lake", "河边" to "by the river", "瀑布" to "a waterfall",
        "城市" to "a city", "街道" to "a street", "城市街道" to "a city street", "小巷" to "a narrow alley",
        "咖啡馆" to "a cozy cafe", "图书馆" to "a library", "卧室" to "a bedroom", "客厅" to "a living room",
        "厨房" to "a kitchen", "教室" to "a classroom", "办公室" to "an office", "寺庙" to "a temple",
        "教堂" to "a church", "城堡" to "a castle", "废墟" to "ancient ruins", "灯塔" to "a lighthouse",
        "花园" to "a garden", "樱花" to "cherry blossoms", "竹林" to "a bamboo forest",
        "古街" to "an old street", "码头" to "a dock", "火车站" to "a train station",
        "地铁" to "a subway station", "天桥" to "a pedestrian bridge", "天空" to "the sky",
        "星空" to "a starry sky", "云海" to "a sea of clouds", "宇宙" to "outer space",
        "太空" to "outer space", "月球" to "the moon", "火星" to "Mars", "极光" to "the aurora",
        // ---------------- 风格 ----------------
        "赛博朋克" to "cyberpunk", "蒸汽朋克" to "steampunk", "末日" to "post apocalyptic",
        "水墨" to "chinese ink wash painting", "水墨画" to "chinese ink wash painting",
        "中国风" to "chinese traditional style", "国风" to "chinese traditional style",
        "油画" to "oil painting", "水彩" to "watercolor painting", "素描" to "pencil sketch",
        "漫画" to "comic book style", "动漫" to "japanese anime style", "二次元" to "anime style",
        "插画" to "digital illustration", "像素风" to "pixel art", "3d" to "3d render",
        "渲染" to "3d render", "写实" to "photorealistic", "摄影" to "photography",
        "卡通" to "cartoon style", "极简" to "minimalist", "复古" to "vintage",
        "未来感" to "futuristic", "哥特" to "gothic", "巴洛克" to "baroque",
        "印象派" to "impressionism", "浮世绘" to "ukiyo-e", "波普" to "pop art",
        "低多边形" to "low poly", "粘土" to "claymation", "纸艺" to "paper art",
        "霓虹" to "neon lights", "胶片" to "film photography", "黑白" to "black and white",
        // ---------------- 光照 / 氛围 ----------------
        "黄金时刻" to "golden hour lighting", "日落" to "sunset", "日出" to "sunrise",
        "黄昏" to "at dusk", "清晨" to "early morning", "夜晚" to "at night",
        "月光" to "moonlight", "逆光" to "backlighting", "侧光" to "side lighting",
        "柔光" to "soft lighting", "电影光" to "cinematic lighting", "影棚灯" to "studio lighting",
        "烛光" to "candlelight", "篝火" to "a campfire", "雾气" to "fog", "薄雾" to "light mist",
        "下雨" to "in the rain", "下雪" to "snowfall", "彩虹" to "a rainbow",
        "梦幻" to "dreamy atmosphere", "神秘" to "mysterious atmosphere", "安静" to "quiet atmosphere",
        "温暖" to "warm tones", "冷色调" to "cool color palette", "高对比" to "high contrast",
        // ---------------- 镜头 / 构图 ----------------
        "特写" to "close-up shot", "半身" to "half body portrait", "全身" to "full body shot",
        "远景" to "wide shot", "鸟瞰" to "aerial view", "俯拍" to "top down view",
        "仰拍" to "low angle shot", "微距" to "macro shot", "景深" to "shallow depth of field",
        "虚化" to "bokeh background", "广角" to "wide angle lens", "长焦" to "telephoto lens",
        "鱼眼" to "fisheye lens", "电影感" to "cinematic composition", "对称" to "symmetrical composition",
        // ---------------- 动作 ----------------
        "跑" to "running", "奔跑" to "running fast", "站" to "standing", "站着" to "standing",
        "坐" to "sitting", "坐着" to "sitting", "躺" to "lying down", "跳" to "jumping",
        "飞" to "flying", "游泳" to "swimming", "跳舞" to "dancing", "笑" to "smiling",
        "哭" to "crying", "看书" to "reading a book", "睡觉" to "sleeping", "吃东西" to "eating",
        "喝咖啡" to "drinking coffee", "弹吉他" to "playing guitar", "唱歌" to "singing",
        "拥抱" to "hugging", "牵手" to "holding hands", "散步" to "walking", "回头" to "looking back",
        "思考" to "thinking", "看镜头" to "looking at camera",
        // ---------------- 服饰 / 外观 ----------------
        "穿着" to "wearing", "裙子" to "a dress", "长裙" to "a long dress", "西装" to "a suit",
        "和服" to "a kimono", "汉服" to "hanfu", "校服" to "a school uniform", "军装" to "a military uniform",
        "帽子" to "a hat", "墨镜" to "sunglasses", "眼镜" to "glasses", "围巾" to "a scarf",
        "长发" to "long hair", "短发" to "short hair", "卷发" to "curly hair", "马尾" to "a ponytail",
        "翅膀" to "wings", "盔甲" to "armor", "披风" to "a cape",
        // ---------------- 颜色 ----------------
        "红色" to "red", "蓝色" to "blue", "绿色" to "green", "黄色" to "yellow",
        "紫色" to "purple", "粉色" to "pink", "白色" to "white", "黑色" to "black",
        "金色" to "golden", "银色" to "silver", "橙色" to "orange", "灰色" to "gray",
        // ---------------- 物体 ----------------
        "车" to "a car", "汽车" to "a car", "跑车" to "a sports car", "摩托车" to "a motorcycle",
        "自行车" to "a bicycle", "飞机" to "an airplane", "船" to "a boat", "帆船" to "a sailboat",
        "热气球" to "a hot air balloon", "火车" to "a train", "房子" to "a house", "小屋" to "a cabin",
        "树" to "a tree", "大树" to "a big tree", "花" to "flowers", "玫瑰" to "roses",
        "向日葵" to "sunflowers", "荷叶" to "lotus leaves", "灯" to "a lamp", "路灯" to "a street lamp",
        "窗户" to "a window", "门" to "a door", "书" to "a book", "咖啡" to "a cup of coffee",
        "蛋糕" to "a cake", "面包" to "bread", "水果" to "fruit", "苹果" to "an apple",
        "西瓜" to "a watermelon", "伞" to "an umbrella", "气球" to "balloons", "风筝" to "a kite",
        // ---------------- 形容词 ----------------
        "可爱" to "cute", "美丽" to "beautiful", "漂亮" to "beautiful", "帅气" to "handsome",
        "古老" to "ancient", "巨大" to "giant", "微小" to "tiny", "明亮" to "bright",
        "黑暗" to "dark", "华丽" to "ornate", "破旧" to "worn out", "干净" to "clean",
        "精致" to "delicate", "壮丽" to "majestic", "宁静" to "serene", "孤独" to "lonely",
        "快乐" to "joyful", "悲伤" to "sorrowful", "威严" to "majestic",
        // ---------------- 画质（用户常写） ----------------
        "高清" to "high resolution", "超清" to "ultra detailed", "细节" to "fine details",
        "杰作" to "masterpiece", "专业" to "professional", "获奖" to "award winning",
    )

    private val CJK = Regex("[\\u4e00-\\u9fa5]")
    private const val MAX_TAG_CHARS = 6

    fun hasChinese(text: String): Boolean = CJK.containsMatchIn(text)

    /**
     * 转成英文 tag 串。返回 null 表示一个词都没认出来（让调用方提示用户改用英文）。
     */
    fun translate(input: String): String? {
        if (!hasChinese(input)) return input

        var text = input
        STOP_WORDS.forEach { text = text.replace(it, " ") }

        val chunks = text.split(Regex("[，。、；：！？,.;:!?\\s()（）\\[\\]【】\"'“”‘’]+"))
            .filter { it.isNotBlank() }

        val tags = LinkedHashSet<String>()
        for (chunk in chunks) {
            var i = 0
            while (i < chunk.length) {
                var matched = false
                val maxLen = minOf(MAX_TAG_CHARS, chunk.length - i)
                var len = maxLen
                while (len > 0) {
                    val en = DICT[chunk.substring(i, i + len)]
                    if (en != null) {
                        tags.add(en)
                        i += len
                        matched = true
                        break
                    }
                    len--
                }
                if (!matched) i++
            }
        }

        if (tags.isEmpty()) return null
        return tags.joinToString(", ")
    }
}
