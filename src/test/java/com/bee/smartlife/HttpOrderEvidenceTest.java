package com.bee.smartlife;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class HttpOrderEvidenceTest {
 @Test void acceptedIntentCanResolveToMatchingTerminalRejection() {
  var rows=List.of(new HttpOrderEvidence.Outcome(10,1,3,"key-a","CREATED",null),new HttpOrderEvidence.Outcome(20,2,3,"key-b","REJECTED","REJECTED_OUT_OF_STOCK"));
  var attempts=List.of(new HttpOrderEvidence.Attempt(0,1,"key-a",10L,null),new HttpOrderEvidence.Attempt(1,2,"key-b",20L,null));
  assertThatCode(()->HttpOrderEvidence.assertIdentities(attempts,rows,3)).doesNotThrowAnyException();
 }
 @Test void swappedClientOrderIdsCannotPassReconciliation() {
  var rows=List.of(new HttpOrderEvidence.Outcome(10,1,3,"key-a","CREATED",null),new HttpOrderEvidence.Outcome(20,2,3,"key-b","CREATED",null));
  var swapped=List.of(new HttpOrderEvidence.Attempt(0,1,"key-a",20L,null),new HttpOrderEvidence.Attempt(1,2,"key-b",10L,null));
  assertThatThrownBy(()->HttpOrderEvidence.assertIdentities(swapped,rows,3)).isInstanceOf(AssertionError.class);
 }
 @Test void sameUserSwappedKeysAndRejectedReturnedIdCannotPassReconciliation() {
  var rows=List.of(new HttpOrderEvidence.Outcome(10,1,3,"key-a","CREATED",null),new HttpOrderEvidence.Outcome(20,1,3,"key-b","REJECTED","REJECTED_ALREADY_PURCHASED"));
  var swapped=List.of(new HttpOrderEvidence.Attempt(0,1,"key-a",20L,null),new HttpOrderEvidence.Attempt(1,1,"key-b",10L,null));
  assertThatThrownBy(()->HttpOrderEvidence.assertIdentities(swapped,rows,3)).isInstanceOf(AssertionError.class);
 }
}
