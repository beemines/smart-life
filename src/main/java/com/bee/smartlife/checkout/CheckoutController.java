package com.bee.smartlife.checkout;

import com.bee.smartlife.dto.Result;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/checkout")
@RequiredArgsConstructor
public class CheckoutController {
  private final CheckoutService bills;
  private final CheckoutRuleService rules;
  @GetMapping("/capabilities") public Result capabilities(){return Result.ok(rules.capabilities());}
  @PostMapping("/bills") public Result create(@RequestBody JsonNode input){return Result.ok(bills.create(input));}
  @GetMapping("/bills") public Result mine(@RequestParam(required=false) Integer limit,@RequestParam(required=false) String beforeTime,@RequestParam(required=false) Long beforeId){return Result.ok(bills.mine(limit,beforeTime,beforeId));}
  @GetMapping("/bills/{id}") public Result detail(@PathVariable long id){return Result.ok(bills.detail(id,false));}
  @GetMapping("/merchant/bills/{id}") public Result merchant(@PathVariable long id){return Result.ok(bills.detail(id,true));}
  @PostMapping("/merchant/bills/{id}/confirm") public Result confirm(@PathVariable long id,@RequestBody JsonNode input){return Result.ok(bills.confirm(id,input));}
  @PostMapping("/bills/{id}/cancel") public Result cancel(@PathVariable long id){return Result.ok(bills.cancel(id));}
  @GetMapping("/bills/{id}/options") public Result options(@PathVariable long id,@RequestParam(required=false) Long beforeVoucherId){return Result.ok(bills.options(id,beforeVoucherId));}
  @PostMapping("/bills/{id}/quote") public Result quote(@PathVariable long id,@RequestBody JsonNode input){return Result.ok(bills.quote(id,input));}
  @PostMapping("/bills/{id}/pay") public Result pay(@PathVariable long id,@RequestBody JsonNode input){return Result.ok(bills.pay(id,input));}
  @PostMapping("/merchant/bills/{id}/reverse") public Result reverse(@PathVariable long id,@RequestBody JsonNode input){return Result.ok(bills.reverse(id,input));}
  @GetMapping("/rules") public Result listRules(@RequestParam long shopId){return Result.ok(rules.list(shopId));}
  @PostMapping("/rules") public Result writeRule(@RequestBody JsonNode input){return Result.ok(rules.write(input));}
  @PostMapping("/coupons") public Result grant(@RequestBody JsonNode input){return Result.ok(rules.grant(input));}
  @PostMapping("/coupons/{id}/revoke") public Result revoke(@PathVariable long id){return Result.ok(rules.revoke(id));}
}
