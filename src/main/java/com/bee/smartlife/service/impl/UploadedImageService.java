package com.bee.smartlife.service.impl;

import com.bee.smartlife.security.ApiException;
import com.bee.smartlife.security.ShopPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UploadedImageService {
    private static final Pattern NAME=Pattern.compile("/blogs/[0-9a-f]/[0-9a-f]/[0-9a-f-]{36}\\.(png|jpg)");
    private final ImageUploadValidator validator;
    private final ShopPermission permission;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    @Value("${smartlife.upload-dir:./uploads}") private String directory;

    public String store(MultipartFile file) {
        long user=permission.currentUser();
        var image=validator.validate(file);
        String id=UUID.randomUUID().toString();
        int hash=id.hashCode();
        String name="/blogs/"+Integer.toHexString(hash&15)+"/"+Integer.toHexString((hash>>4)&15)+"/"+id+"."+image.suffix();
        Path path=resolve(name);Path temporary=null;boolean created=false;
        rejectSymlinks(path); // Check existing ancestry before creating anything.
        try {
            Files.createDirectories(path.getParent());
            rejectSymlinks(path);
            temporary=Files.createTempFile(path.getParent(),".upload-",".tmp");
            Files.write(temporary,image.bytes());
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE);
            created=true;
            transaction().executeWithoutResult(status -> jdbc.update("INSERT INTO tb_uploaded_file(name,user_id,format,size_bytes,state) VALUES (?,?,?,?,'ACTIVE')",name,user,image.suffix(),image.bytes().length));
            return name;
        } catch(IOException|RuntimeException failure) {
            try {if(created)Files.deleteIfExists(path);if(temporary!=null)Files.deleteIfExists(temporary);} catch(IOException cleanup){failure.addSuppressed(cleanup);}
            if(failure instanceof ApiException invalid)throw invalid;
            throw new IllegalStateException("图片保存失败",failure);
        }
    }

    public void delete(String name) {
        long user=permission.currentUser();Path requested=resolve(name);
        transaction().executeWithoutResult(status -> {
            var rows=jdbc.queryForList("SELECT name,user_id,state FROM tb_uploaded_file WHERE name=? FOR UPDATE",name);
            if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"文件记录不存在");
            var row=rows.getFirst();
            if(((Number)row.get("user_id")).longValue()!=user) throw new ApiException(HttpStatus.FORBIDDEN,"无权删除该文件");
            if("DELETED".equals(row.get("state"))) return;
            if(!"ACTIVE".equals(row.get("state")))throw new ApiException(HttpStatus.CONFLICT,"文件状态不允许删除");
            Path path=resolve((String)row.get("name"));
            if(!path.equals(requested)) throw ApiException.badRequest("文件标识不正确");
            try {rejectSymlinks(path);Files.deleteIfExists(path);} catch(IOException e){throw new IllegalStateException("文件删除失败",e);}
            if(jdbc.update("UPDATE tb_uploaded_file SET state='DELETED' WHERE name=? AND user_id=? AND state='ACTIVE'",name,user)!=1) throw new IllegalStateException("文件删除状态未保存");
        });
    }

    private TransactionTemplate transaction() {
        var tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);return tx;
    }
    private Path resolve(String name) {
        if(name==null||!NAME.matcher(name).matches()) throw ApiException.badRequest("请使用原始文件标识");
        Path root=Paths.get(directory).toAbsolutePath().normalize();
        Path path=root.resolve(name.substring(1)).normalize();
        if(!path.startsWith(root)||path.equals(root)) throw ApiException.badRequest("文件路径越界");
        return path;
    }
    private void rejectSymlinks(Path path) {
        Path root=Paths.get(directory).toAbsolutePath().normalize();
        Path realRoot;
        try{realRoot=Files.exists(root)?root.toRealPath():root;}catch(IOException invalid){throw ApiException.badRequest("上传目录不能访问");}
        for(Path current=path;current!=null&&current.startsWith(root);current=current.getParent()) {
            if(Files.isSymbolicLink(current)) throw ApiException.badRequest("不支持符号链接路径");
            // Windows directory junctions are not necessarily reported as symbolic links.
            try{if(Files.exists(current)&&!current.toRealPath().startsWith(realRoot))throw ApiException.badRequest("文件路径越界");}
            catch(IOException invalid){throw ApiException.badRequest("上传路径不能访问");}
        }
    }
}
