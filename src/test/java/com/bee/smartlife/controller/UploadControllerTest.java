package com.bee.smartlife.controller;

import com.bee.smartlife.dto.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UploadControllerTest {
    @TempDir
    Path root;

    @Test
    void uploadsAndDeletesWithinTheConfiguredDirectory() throws Exception {
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "imageUploadDir", root.toString());
        byte[] image = new byte[]{1, 2, 3};
        Result result = controller.uploadImage(new MockMultipartFile("file", "photo.png", "image/png", image));
        assertTrue(result.getSuccess());
        String name = (String) result.getData();
        assertTrue(name.startsWith("/blogs/"));
        Path file = root.resolve(name.substring(1));
        assertArrayEquals(image, Files.readAllBytes(file));
        assertTrue(controller.deleteBlogImg(name).getSuccess());
        assertFalse(Files.exists(file));
    }

    @Test
    void refusesToDeleteAFileOutsideTheConfiguredDirectory() throws Exception {
        Path uploads = Files.createDirectory(root.resolve("uploads"));
        Path outside = root.resolve("private.txt");
        Files.write(outside, new byte[]{9});
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "imageUploadDir", uploads.toString());
        assertFalse(controller.deleteBlogImg("../private.txt").getSuccess());
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(outside));
    }

    @Test
    void refusesToDeleteTheUploadRoot() throws Exception {
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "imageUploadDir", root.toString());
        Path existing = root.resolve("existing.png");
        Files.write(existing, new byte[]{4});
        assertFalse(controller.deleteBlogImg("/").getSuccess());
        assertTrue(Files.exists(existing));
    }
}
