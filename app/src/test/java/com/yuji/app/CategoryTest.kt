package com.yuji.app
import org.junit.Assert.assertEquals
import org.junit.Test
class CategoryTest {
    @Test fun apiExpense() {assertEquals("数字服务/AI与API",Categories.normalize("deepseekapi","其他",false))}
    @Test fun headphones() {assertEquals("购物/数码耳机",Categories.normalize("FreeClip2","购物",false))}
    @Test fun incomeNotExpense() {assertEquals("收入/兼职",Categories.normalize("API兼职","收入/兼职",true))}
    @Test fun unknownFallback() {assertEquals("其他/待分类",Categories.normalize("未知商品","瞎编分类",false))}
}
