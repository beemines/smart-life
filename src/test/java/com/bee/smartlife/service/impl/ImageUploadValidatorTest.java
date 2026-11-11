package com.bee.smartlife.service.impl;

import com.bee.smartlife.security.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class ImageUploadValidatorTest {
    final ImageUploadValidator validator=new ImageUploadValidator();
    @Test void validPngAndJpegAreDecodedAndReencoded() throws Exception {
        for(String format:new String[]{"png","jpeg"}) {
            var result=validator.validate(file("real."+format,"image/"+format,image(format)));
            assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.bytes())).getWidth()).isEqualTo(2);
            assertThat(result.suffix()).isEqualTo(format.equals("png")?"png":"jpg");
        }
    }
    @Test void arbitraryBytesAndEmptyFilesAreNotImages() {
        for(byte[] bytes:new byte[][]{new byte[0],new byte[]{1,2,3}}) assertThatThrownBy(()->validator.validate(file("photo.png","image/png",bytes))).isInstanceOf(ApiException.class);
    }
    @Test void suffixMimeAndActualFormatMustAllAgree() throws Exception {
        byte[] png=image("png"),jpeg=image("jpeg");
        assertThatThrownBy(()->validator.validate(file("photo.txt","image/png",png))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->validator.validate(file("photo.png","image/jpeg",png))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->validator.validate(file("photo.png","image/png",jpeg))).isInstanceOf(ApiException.class);
    }
    @Test void oversizedFileIsRejectedBeforeDecoding() {
        assertThatThrownBy(()->validator.validate(file("photo.png","image/png",new byte[(int)ImageUploadValidator.MAX_BYTES+1]))).isInstanceOf(ApiException.class);
    }
    @Test void dimensionsAreInspectedBeforeAllocatingTheDecodedRaster() throws Exception {
        for(int[] size:new int[][]{{8193,1},{5000,5000}}) {
            byte[] png=image("png");
            ByteBuffer.wrap(png).putInt(16,size[0]).putInt(20,size[1]);
            CRC32 crc=new CRC32();crc.update(png,12,17);ByteBuffer.wrap(png).putInt(29,(int)crc.getValue());
            assertThatThrownBy(()->validator.validate(file("photo.png","image/png",png))).isInstanceOf(ApiException.class).hasMessage("图片尺寸过大");
        }
    }
    @Test void reencodingDoesNotPreserveAppendedContent() throws Exception {
        byte[] clean=image("png"),extra=Arrays.copyOf(clean,clean.length+100);
        Arrays.fill(extra,clean.length,extra.length,(byte)'x');
        assertThat(validator.validate(file("photo.png","image/png",extra)).bytes().length).isLessThan(extra.length);
    }
    @Test void originalMultipartStreamIsClosedOnSuccessfulAndFailedValidation() throws Exception {
        for(byte[] bytes:new byte[][]{image("png"),new byte[]{1,2,3}}) {
            AtomicBoolean closed=new AtomicBoolean();
            var upload=new MockMultipartFile("file","photo.png","image/png",bytes){
                @Override public InputStream getInputStream(){return new ByteArrayInputStream(bytes){@Override public void close(){closed.set(true);}};}
            };
            try{validator.validate(upload);}catch(ApiException invalid){assertThat(bytes.length).isEqualTo(3);}
            assertThat(closed).as("raw multipart stream must close").isTrue();
        }
    }
    MockMultipartFile file(String name,String mime,byte[] bytes){return new MockMultipartFile("file",name,mime,bytes);}
    byte[] image(String format)throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),format,out);return out.toByteArray();}
}
