package io.github.localledger.core

object CategoryIcons {
    val choices = listOf("meal" to "餐饮", "cup" to "饮品", "fruit" to "水果", "snack" to "零食",
        "basket" to "买菜", "transport" to "交通", "car" to "汽车", "travel" to "旅行", "phone" to "通讯",
        "device" to "数码", "home" to "住房", "wallet" to "钱包", "chart" to "理财", "bag" to "购物",
        "beauty" to "美妆", "gift" to "礼品", "heart" to "心愿", "baby" to "宝贝", "pet" to "宠物",
        "shirt" to "服饰", "book" to "学习", "sport" to "运动", "medical" to "医疗", "bolt" to "水电",
        "game" to "娱乐", "star" to "星标", "grid" to "其他", "wifi" to "网络", "work" to "工作",
        "calendar" to "日历", "music" to "音乐", "camera" to "摄影", "tools" to "工具",
        "wine" to "烟酒", "handshake" to "人情", "award" to "奖章", "rent" to "钥匙",
        "envelopein" to "收红包", "envelopeout" to "发红包", "incomeplus" to "收入", "coins" to "硬币",
        "interest" to "利率", "aacollection" to "分摊", "reimbursement" to "票据", "refund" to "退款")
    val keys = choices.map { it.first }.toSet()
}
