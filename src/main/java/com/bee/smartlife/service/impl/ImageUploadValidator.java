package com.bee.smartlife.service.impl;

import com.bee.smartlife.security.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.util.Locale;

@Component
public class ImageUploadValidator {
    public static final long MAX_BYTES=5L*1024*1024;
    public record Image(byte[] bytes,String suffix,String mime) {}

    public Image validate(MultipartFile file) {
        if(file==null||file.isEmpty()||file.getSize()>MAX_BYTES) throw ApiException.badRequest("图片为空或超过5MiB");
        String name=file.getOriginalFilename();
        String suffix=name==null||!name.contains(".")?"":name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        String expected=suffix.equals("png")?"png":suffix.equals("jpg")||suffix.equals("jpeg")?"jpeg":null;
        String mime=expected==null?null:expected.equals("png")?"image/png":"image/jpeg";
        if(expected==null||!mime.equalsIgnoreCase(file.getContentType())) throw ApiException.badRequest("仅支持类型与内容一致的PNG/JPEG图片");
        try(var raw=file.getInputStream();var input=new MemoryCacheImageInputStream(raw)) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw ApiException.badRequest("文件不是有效图片");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                if(!expected.equalsIgnoreCase(reader.getFormatName())) throw ApiException.badRequest("图片内容与后缀不一致");
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>8192||height>8192||(long)width*height>20_000_000) throw ApiException.badRequest("图片尺寸过大");
                var decoded=reader.read(0);
                if(decoded==null) throw ApiException.badRequest("图片不能解码");
                var encoded=new ByteArrayOutputStream();
                if(!ImageIO.write(decoded,expected,encoded)||encoded.size()>MAX_BYTES) throw ApiException.badRequest("图片编码失败或超过5MiB");
                return new Image(encoded.toByteArray(),expected.equals("png")?"png":"jpg",mime);
            } finally { reader.dispose(); }
        } catch(IOException|IllegalArgumentException invalid) {
            throw ApiException.badRequest("图片内容不完整或不能解码");
        }
    }
}
