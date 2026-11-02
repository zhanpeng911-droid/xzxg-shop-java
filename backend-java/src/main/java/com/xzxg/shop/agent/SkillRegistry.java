package com.xzxg.shop.agent;

import com.xzxg.shop.domain.AgentBlock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The six navigation/help skills from the Go skills.go. */
@Service
public class SkillRegistry {

    public ToolRegistry.Observation execute(String skill) {
        return switch (skill) {
            case "navigate_cart" -> action(skill, "可以去购物车查看已选商品。", "打开购物车", "cart");
            case "navigate_orders" -> action(skill, "可以去订单页查看订单、支付和物流状态。", "查看订单", "orders");
            case "navigate_products" -> action(skill, "可以去商品页浏览和筛选当前商品库。", "打开商品页", "products");
            case "coupon_help" -> action(skill, "优惠券入口在用户首页和结算相关区域；领取后可在可用优惠券列表查看门槛和有效期。", "查看优惠入口", "home");
            case "order_help" -> action(skill, "订单页可以查看待支付、待收货、已完成订单；物流和售后入口也从订单详情进入。", "查看订单", "orders");
            case "after_sales_help" -> action(skill, "售后需要先定位对应订单和商品，再在订单详情里发起退换货或联系商家处理。", "去订单页处理售后", "orders");
            default -> ToolRegistry.Observation.fail(skill, "未知 skill：" + skill);
        };
    }

    private static ToolRegistry.Observation action(String skill, String message, String label,
                                                   String target) {
        AgentBlock block = Blocks.action(message, "navigate", target, label);
        return new ToolRegistry.Observation(skill, true, message,
                Map.of("skill", skill, "action", block.action(), "message", message),
                List.of(block));
    }
}
