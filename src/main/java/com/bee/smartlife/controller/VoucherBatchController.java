package com.bee.smartlife.controller;

import com.bee.smartlife.dto.Result;
import com.bee.smartlife.service.impl.VoucherBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/voucher/batches")
@RequiredArgsConstructor
public class VoucherBatchController {
 private final VoucherBatchService batches;
 public record Submit(String voucherId,String requestKey,List<String> userIds) {}
 @PostMapping public Result submit(@RequestBody Submit body){return Result.ok(batches.submit(body));}
 @PostMapping("/import") public Result upload(@RequestParam String voucherId,@RequestParam String requestKey,@RequestParam MultipartFile file){return Result.ok(batches.upload(voucherId,requestKey,file));}
 @GetMapping public Result list(@RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="20") int limit){return Result.ok(batches.list(beforeId,limit));}
 @GetMapping("/{id}") public Result detail(@PathVariable long id,@RequestParam(required=false) Integer beforeRow,@RequestParam(defaultValue="100") int limit){return Result.ok(batches.detail(id,beforeRow,limit));}
 @PostMapping("/{id}/retry") public Result retry(@PathVariable long id){return Result.ok(batches.retry(id));}
}
