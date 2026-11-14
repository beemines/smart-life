package com.bee.smartlife;

import static org.assertj.core.api.Assertions.*;
import java.util.*;

/** Test-side reconciliation shared by real HTTP workloads and corrupted-evidence regressions. */
final class HttpOrderEvidence {
 record Attempt(int caller,long user,String key,Long acceptedId,String error) {}
 record Outcome(long order,long user,long voucher,String key,String state,String reason) {}
 static void assertIdentities(List<Attempt> attempts,List<Outcome> outcomes,long voucher) {
  assertThat(outcomes).hasSize(attempts.size());
  var returned=new HashSet<Long>();
  for(var attempt:attempts) {
   var matching=outcomes.stream().filter(row->row.user()==attempt.user() && row.key().equals(attempt.key())).toList();
   assertThat(matching).as("caller %s user/key identity",attempt.caller()).hasSize(1);
   var row=matching.getFirst();assertThat(row.voucher()).isEqualTo(voucher);
   assertThat(row.state()).isIn("CREATED","REJECTED");
   if(attempt.acceptedId()!=null) {assertThat(row.order()).isEqualTo(attempt.acceptedId());assertThat(returned.add(attempt.acceptedId())).isTrue();}
   else {assertThat(row.state()).isEqualTo("REJECTED");assertThat(row.reason()).isEqualTo(attempt.error().equals("库存不足")?"REJECTED_OUT_OF_STOCK":"REJECTED_ALREADY_PURCHASED");}
  }
  assertThat(returned).containsAll(outcomes.stream().filter(row->row.state().equals("CREATED")).map(Outcome::order).toList());
 }
 static String csv(Object... values) {return Arrays.stream(values).map(value->"\""+Objects.toString(value,"").replace("\"","\"\"")+"\"").collect(java.util.stream.Collectors.joining(","));}
}
