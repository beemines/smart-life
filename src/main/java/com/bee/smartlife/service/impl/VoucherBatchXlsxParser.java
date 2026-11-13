package com.bee.smartlife.service.impl;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.apache.poi.openxml4j.opc.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.util.XMLHelper;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Event reader with explicit workbook-wide budgets (including unused shared strings). */
public class VoucherBatchXlsxParser {
 private static final long FILE_LIMIT=5L*1024*1024,EXPANSION_LIMIT=20L*1024*1024;
 static {ZipSecureFile.setMinInflateRatio(.01);ZipSecureFile.setMaxEntrySize(EXPANSION_LIMIT);ZipSecureFile.setMaxTextSize(10L*1024*1024);}
 public void parse(MultipartFile file,java.util.function.Consumer<VoucherBatchService.Input> sink) throws Exception {
   if(file==null||file.isEmpty()||file.getSize()>FILE_LIMIT||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw new IOException("xlsx required");
   Path temp=Files.createTempFile("voucher-batch-",".xlsx");
   try{
     try(var in=file.getInputStream();var out=Files.newOutputStream(temp)){long count=0;byte[] buffer=new byte[8192];int read;while((read=in.read(buffer))!=-1){count+=read;if(count>FILE_LIMIT)throw new IOException("file limit");out.write(buffer,0,read);}}
     try(var zip=new ZipFile(temp.toFile())){var entries=zip.entries();long total=0;int count=0;while(entries.hasMoreElements()){var e=entries.nextElement();if(++count>100||e.getSize()<0||(total+=e.getSize())>EXPANSION_LIMIT)throw new IOException("ZIP budget");}}
     try(var pkg=OPCPackage.open(temp.toFile(),PackageAccess.READ)){
       for(var part:pkg.getParts())if(part.getContentType().toLowerCase(Locale.ROOT).contains("macro")||part.getContentType().toLowerCase(Locale.ROOT).contains("vba"))throw new IOException("only non-macro xlsx supported");
       var reader=new XSSFReader(pkg);var shared=new ArrayList<String>();
       org.apache.poi.xssf.model.StylesTable styles=null;
       try(var data=reader.getStylesData()){
         if(data!=null){byte[] bytes=data.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new IOException("style budget");styles=new org.apache.poi.xssf.model.StylesTable(new ByteArrayInputStream(bytes));if(styles.getNumCellStyles()>10001)throw new IOException("style count");}
       }
       try(var data=reader.getSharedStringsData()){if(data!=null)read(data,new DefaultHandler(){StringBuilder value;int chars;
         @Override public void startElement(String u,String l,String q,Attributes a)throws SAXException{if(l.equals("si")){if(shared.size()>=10001)throw new SAXException("shared string count");value=new StringBuilder();}}
         @Override public void characters(char[] c,int s,int n)throws SAXException{if(value!=null){if((chars+=n)>1_300_000||value.length()+n>128)throw new SAXException("shared string budget");value.append(c,s,n);}}
         @Override public void endElement(String u,String l,String q){if(l.equals("si")){shared.add(value.toString());value=null;}}
       });}
       var sheets=reader.getSheetsData();if(!sheets.hasNext())throw new IOException("missing sheet");
       var handler=new Sheet(shared,styles,sink);try(var sheet=sheets.next()){read(sheet,handler);}if(sheets.hasNext())throw new IOException("one sheet required");
       if(!handler.header||handler.count==0)throw new IOException("missing rows");
     }
   }finally{Files.deleteIfExists(temp);}
 }
 private static void read(InputStream in,DefaultHandler h)throws Exception{XMLReader xml=XMLHelper.newXMLReader();xml.setContentHandler(h);xml.parse(new InputSource(in));}
 private static class Sheet extends DefaultHandler {
   final List<String> shared;final org.apache.poi.xssf.model.StylesTable styles;final java.util.function.Consumer<VoucherBatchService.Input> sink;boolean header,formula,inText,dateStyle;String type;StringBuilder text;int row,cells,lastRow,count;
   Integer columnStyle,rowStyle;int columnDefinitions;
   Sheet(List<String> shared,org.apache.poi.xssf.model.StylesTable styles,java.util.function.Consumer<VoucherBatchService.Input> sink){this.shared=shared;this.styles=styles;this.sink=sink;}
   @Override public void startElement(String u,String l,String q,Attributes a)throws SAXException{
     if(l.equals("col")){
       if(++columnDefinitions>10000)throw new SAXException("column definition budget");
       try{int min=Integer.parseInt(a.getValue("min")),max=Integer.parseInt(a.getValue("max"));if(min<1||max<min||max>16384)throw new SAXException("column range");
         if(min==1&&a.getValue("style")!=null){if(columnStyle!=null)throw new SAXException("overlapping column defaults");columnStyle=styleIndex(a.getValue("style"));}
       }catch(NumberFormatException e){throw new SAXException("column range",e);}
     }
     if(l.equals("row")){try{row=Integer.parseInt(a.getValue("r"));}catch(Exception e){throw new SAXException("row number");}if(row<=lastRow||row>10001||count>=10000)throw new SAXException("row budget");lastRow=row;cells=0;text=new StringBuilder();formula=false;type=null;rowStyle=a.getValue("s")==null?null:styleIndex(a.getValue("s"));}
     if(l.equals("c")){if(++cells>1||a.getValue("r")==null||!a.getValue("r").equals("A"+row))throw new SAXException("one column required");type=a.getValue("t");dateStyle=false;
       // Match pinned POI6 XSSFCell.getCellStyle: explicit > column > row > style0.
       if(styles!=null||a.getValue("s")!=null){int index=a.getValue("s")!=null?styleIndex(a.getValue("s")):columnStyle!=null?columnStyle:rowStyle!=null?rowStyle:styleIndex("0");var style=styles.getStyleAt(index);dateStyle=org.apache.poi.ss.usermodel.DateUtil.isADateFormat(style.getDataFormat(),style.getDataFormatString());}
     }
     if(l.equals("f"))formula=true;
     if(l.equals("v")||l.equals("t"))inText=true;
   }
   private int styleIndex(String value)throws SAXException{
     try{int index=Integer.parseInt(value);if(styles==null||index<0||index>=styles.getNumCellStyles())throw new IllegalArgumentException("style index");return index;}catch(Exception e){throw new SAXException("invalid cell style",e);}
   }
   @Override public void characters(char[] c,int s,int n)throws SAXException{if(inText){if(text.length()+n>128)throw new SAXException("cell budget");text.append(c,s,n);}}
   @Override public void endElement(String u,String l,String q)throws SAXException{
     if(l.equals("v")||l.equals("t"))inText=false;
     if(l.equals("row")){
       String value=text.toString();if("s".equals(type)){try{value=shared.get(Integer.parseInt(value));}catch(Exception e){throw new SAXException("shared string index");}}
       if(!header){if(row!=1||formula||!value.equals("userId"))throw new SAXException("header must be userId");header=true;return;}
       if(formula)value="[公式不可用]";
       else if(!"s".equals(type)&&!"inlineStr".equals(type)){
         try{
           if(type!=null&&!type.equals("n"))throw new NumberFormatException("not numeric");
           if(dateStyle)throw new NumberFormatException("date/time is not an ID");
           var number=new java.math.BigDecimal(value).stripTrailingZeros();
           // Check scale using long arithmetic BEFORE expanding an exponent to an integer.
           if(number.signum()<=0||number.scale()>0||(long)number.precision()-number.scale()>15)
             throw new NumberFormatException("ambiguous numeric ID");
           value=number.toBigIntegerExact().toString();
         }catch(ArithmeticException|NumberFormatException invalid){value="[数字ID不明确]";}
       }
       sink.accept(new VoucherBatchService.Input(row,value));count++;
     }
   }
 }
}
