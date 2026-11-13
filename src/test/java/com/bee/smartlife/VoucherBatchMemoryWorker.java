package com.bee.smartlife;
import com.bee.smartlife.service.impl.VoucherBatchXlsxParser;
import java.io.*;
import java.lang.management.ManagementFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;
public class VoucherBatchMemoryWorker {
 public static void main(String[] args)throws Exception{
   int count=Integer.parseInt(args[0]);byte[] bytes;
   try(var w=new SXSSFWorkbook(100);var out=new ByteArrayOutputStream()){var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");for(int i=1;i<=count;i++)s.createRow(i).createCell(0).setCellValue(Long.toString(1000000000000000L+i));w.write(out);bytes=out.toByteArray();}
   System.gc();long before=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();for(var p:ManagementFactory.getMemoryPoolMXBeans())p.resetPeakUsage();
   var rows=new java.util.concurrent.atomic.AtomicInteger();new VoucherBatchXlsxParser().parse(new MockMultipartFile("file","users.xlsx","xlsx",bytes),row->rows.incrementAndGet());
   if(rows.get()!=count)throw new AssertionError("row count");long peak=0;for(var p:ManagementFactory.getMemoryPoolMXBeans())if(p.getType()==java.lang.management.MemoryType.HEAP)peak+=p.getPeakUsage().getUsed();
   System.out.println("rows="+count+" compressedBytes="+bytes.length+" heapBefore="+before+" heapPeakPools="+peak+" callbackRows="+rows.get()+" maxRowBufferChars=128");
 }
}
