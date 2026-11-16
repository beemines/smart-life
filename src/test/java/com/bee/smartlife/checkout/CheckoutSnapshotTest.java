package com.bee.smartlife.checkout;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CheckoutSnapshotTest {
 @Test void databaseJsonRoundTripMustCompareNumericValueNotJacksonNodeWidth() throws Exception {
   var mapper=new ObjectMapper();var c=CheckoutCalculator.calculate(18000,2000,null,null,null);
   var original=mapper.valueToTree(c);var stored=mapper.readTree(mapper.writeValueAsString(c));
   // Jackson creates LongNode from Java long, but reads small JSON integers as IntNode.
   assertThat(original.get("payable").getClass()).isNotEqualTo(stored.get("payable").getClass());
   assertThat(CheckoutService.sameCalculation(mapper,c,stored)).isTrue();
   ((com.fasterxml.jackson.databind.node.ObjectNode)stored).put("payable",17999);
   assertThat(CheckoutService.sameCalculation(mapper,c,stored)).isFalse();
 }
}
