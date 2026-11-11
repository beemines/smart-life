package com.bee.smartlife.controller;

import com.bee.smartlife.dto.Result;
import com.bee.smartlife.service.impl.UploadedImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("upload")
@RequiredArgsConstructor
public class UploadController {
    private final UploadedImageService images;
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) { return Result.ok(images.store(image)); }
    @DeleteMapping("blog")
    public Result deleteBlogImg(@RequestParam("name") String name) { images.delete(name); return Result.ok(); }
}
