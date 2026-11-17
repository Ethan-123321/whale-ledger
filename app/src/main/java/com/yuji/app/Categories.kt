package com.yuji.app

object Categories {
    val expense=listOf("餐饮/正餐","餐饮/早餐","餐饮/外卖","餐饮/奶茶咖啡","餐饮/饮料","餐饮/甜点","餐饮/零食水果","交通/公交地铁","交通/打车","交通/火车机票","交通/油费停车","购物/数码耳机","购物/手机电脑","购物/服饰鞋包","购物/日用品","数字服务/AI与API","数字服务/软件订阅","数字服务/游戏","居住/房租","居住/水电燃气","通讯/话费网络","健康/医疗药品","教育/学习","娱乐/电影活动","人情/礼物红包","旅行/住宿","其他/待分类")
    val income=listOf("收入/工资","收入/奖金","收入/兼职","收入/红包礼金","收入/投资收益","收入/其他")
    val parents = (expense + income).map { it.substringBefore("/") }.toSet()
    fun options(isIncome: Boolean) = if (isIncome) income else expense
    fun fallback(isIncome: Boolean) = if (isIncome) "收入/其他" else "其他/待分类"

    fun migrate(label: String, isIncome: Boolean): String = when {
        label in options(isIncome) -> label
        label == "未分类" || label == "其他" -> fallback(isIncome)
        else -> label
    }

    fun normalize(title:String, proposed:String, isIncome:Boolean, custom: Set<String> = emptySet()):String {
        val allowed=options(isIncome) + custom
        if (proposed in custom) return proposed
        val matchingCustom = custom.sortedByDescending { it.length }.firstOrNull {
            val name = it.substringAfterLast("/")
            name.length >= 2 && title.contains(name, ignoreCase = true)
        }
        if (matchingCustom != null) return matchingCustom
        if(!isIncome) {
            val t=title.lowercase()
            if(Regex("(^|[^a-z])api([^a-z]|$)|deepseek").containsMatchIn(t)) return "数字服务/AI与API"
            if(t.contains("freeclip") || t.contains("耳机")) return "购物/数码耳机"
        }
        if (proposed !in allowed || proposed == fallback(isIncome)) {
            val inferred = when {
                isIncome && "工资" in title -> "收入/工资"
                isIncome && "奖金" in title -> "收入/奖金"
                isIncome && "兼职" in title -> "收入/兼职"
                !isIncome && Regex("午饭|晚饭|午餐|晚餐|正餐").containsMatchIn(title) -> "餐饮/正餐"
                !isIncome && Regex("蛋糕|冰[淇激][淋凌]|雪糕|布丁|甜点").containsMatchIn(title) -> "餐饮/甜点"
                !isIncome && Regex("薯片|乐事|零食|水果").containsMatchIn(title) -> "餐饮/零食水果"
                !isIncome && Regex("咖啡|奶茶").containsMatchIn(title) -> "餐饮/奶茶咖啡"
                !isIncome && Regex("可乐(?!鸡|排骨)|汽水|雪碧|果汁|气泡水|矿泉水").containsMatchIn(title) -> "餐饮/饮料"
                !isIncome && Regex("早餐|早饭").containsMatchIn(title) -> "餐饮/早餐"
                !isIncome && "外卖" in title -> "餐饮/外卖"
                !isIncome && Regex("公交|地铁").containsMatchIn(title) -> "交通/公交地铁"
                !isIncome && "打车" in title -> "交通/打车"
                !isIncome && "房租" in title -> "居住/房租"
                else -> null
            }
            if (inferred != null) return inferred
        }
        return if (proposed in allowed) proposed else fallback(isIncome)
    }
}
