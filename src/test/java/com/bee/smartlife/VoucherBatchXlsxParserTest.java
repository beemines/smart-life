package com.bee.smartlife;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import com.bee.smartlife.service.impl.VoucherBatchXlsxParser;

class VoucherBatchXlsxParserTest {
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"COLUMN_DATE","ROW_DATE","EXPLICIT_GENERAL_COLUMN","EXPLICIT_GENERAL_ROW","COLUMN_GENERAL_ROW_DATE","COLUMN_DATE_ROW_GENERAL"})
 void omittedCellStylesHonorColumnThenRowDefaultsAndExplicitGeneralWins(String scenario)throws Exception{
   var original=workbook(w->{var sheet=w.createSheet();var date=w.createCellStyle();date.setDataFormat(w.createDataFormat().getFormat("yyyy-mm-dd"));
     if(scenario.contains("COLUMN"))sheet.setDefaultColumnStyle(0,scenario.equals("COLUMN_GENERAL_ROW_DATE")?w.getCellStyleAt(0):date);
     sheet.createRow(0).createCell(0).setCellValue("userId");var row=sheet.createRow(1);
     if(scenario.contains("ROW"))row.setRowStyle(scenario.equals("COLUMN_DATE_ROW_GENERAL")?w.getCellStyleAt(0):date);row.createCell(0).setCellValue(45000d);
   });
   var out=new ByteArrayOutputStream();try(var in=new java.util.zip.ZipInputStream(original.getInputStream());var zip=new java.util.zip.ZipOutputStream(out)){
     java.util.zip.ZipEntry entry;while((entry=in.getNextEntry())!=null){byte[] bytes=in.readAllBytes();if(entry.getName().equals("xl/worksheets/sheet1.xml")){String xml=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);var m=java.util.regex.Pattern.compile("<c\\b[^>]*\\br=\"A2\"[^>]*>").matcher(xml);assertThat(m.find()).isTrue();String cell=m.group().replaceAll(" s=\"[^\"]*\"","");if(scenario.startsWith("EXPLICIT"))cell=cell.replace(">"," s=\"0\">");xml=xml.substring(0,m.start())+cell+xml.substring(m.end());bytes=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);}zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));zip.write(bytes);zip.closeEntry();}
   }
   var file=new MockMultipartFile("file","users.xlsx","xlsx",out.toByteArray());boolean dateExpected=scenario.equals("COLUMN_DATE")||scenario.equals("ROW_DATE")||scenario.equals("COLUMN_DATE_ROW_GENERAL");
   // Independently check effective precedence against the pinned POI6 user model.
   try(var model=new XSSFWorkbook(file.getInputStream())){var style=model.getSheetAt(0).getRow(1).getCell(0).getCellStyle();assertThat(org.apache.poi.ss.usermodel.DateUtil.isADateFormat(style.getDataFormat(),style.getDataFormatString())).isEqualTo(dateExpected);}
   String id=parse(file).get(0).userId();if(dateExpected)assertThat(id).doesNotMatch("[0-9]+");else assertThat(id).isEqualTo("45000");
 }
 @Test void defaultDateStyleAppliesWhenNumericCellOmitsItsStyleIndex()throws Exception{
   var original=workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");s.createRow(1).createCell(0).setCellValue(45000d);w.getCellStyleAt(0).setDataFormat(w.createDataFormat().getFormat("yyyy-mm-dd"));});
   var out=new ByteArrayOutputStream();try(var in=new java.util.zip.ZipInputStream(original.getInputStream());var zip=new java.util.zip.ZipOutputStream(out)){
     java.util.zip.ZipEntry entry;while((entry=in.getNextEntry())!=null){byte[] bytes=in.readAllBytes();if(entry.getName().equals("xl/worksheets/sheet1.xml"))bytes=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace(" s=\"0\"","").getBytes(java.nio.charset.StandardCharsets.UTF_8);zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));zip.write(bytes);zip.closeEntry();}
   }
   assertThat(parse(new MockMultipartFile("file","users.xlsx","xlsx",out.toByteArray())).get(0).userId()).doesNotMatch("[0-9]+");
 }
 @Test void dateAndTimeFormattedSerialsAreNotRecipientIds()throws Exception{
   var file=workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");s.createRow(1).createCell(0).setCellValue(45000d);int i=2;for(String format:new String[]{"m/d/yy","yyyy-mm-dd","hh:mm:ss","[h]:mm:ss"}){var style=w.createCellStyle();style.setDataFormat(w.createDataFormat().getFormat(format));var cell=s.createRow(i++).createCell(0);cell.setCellValue(45000d);cell.setCellStyle(style);}});
   var rows=parse(file);assertThat(rows.get(0).userId()).isEqualTo("45000");for(int i=1;i<rows.size();i++)assertThat(rows.get(i).userId()).doesNotMatch("[0-9]+");
 }
 @Test void emitsRowsBeforeMalformedEofInsteadOfCollectingTheWorkbook()throws Exception{
   var file=workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");for(int i=1;i<=101;i++)s.createRow(i).createCell(0).setCellValue(Long.toString(i));s.createRow(102).createCell(1).setCellValue("bad tail");});
   var count=new java.util.concurrent.atomic.AtomicInteger();assertThatThrownBy(()->new VoucherBatchXlsxParser().parse(file,row->count.incrementAndGet())).isInstanceOf(Exception.class);assertThat(count.get()).isEqualTo(101);
 }
 static java.util.List<com.bee.smartlife.service.impl.VoucherBatchService.Input> parse(org.springframework.web.multipart.MultipartFile file)throws Exception {var rows=new java.util.ArrayList<com.bee.smartlife.service.impl.VoucherBatchService.Input>();new VoucherBatchXlsxParser().parse(file,rows::add);return rows;}
 interface WorkbookSetup {void fill(XSSFWorkbook workbook);}
 static MockMultipartFile workbook(WorkbookSetup setup)throws Exception {
   try(var w=new XSSFWorkbook();var out=new ByteArrayOutputStream()){setup.fill(w);w.write(out);return new MockMultipartFile("file","users.xlsx","application/octet-stream",out.toByteArray());}
 }
 @Test void preservesDuplicateSourceRowsAndRejectsFormulaAndAmbiguousNumericRecipients()throws Exception {
   var file=workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");s.createRow(1).createCell(0).setCellValue("9007199254740993");s.createRow(2).createCell(0).setCellValue("9007199254740993");s.createRow(3).createCell(0).setCellFormula("1+2");s.createRow(4).createCell(0).setCellValue(1234567890123456d);});
   var rows=parse(file);assertThat(rows).hasSize(4);assertThat(rows.get(0).userId()).isEqualTo("9007199254740993");assertThat(rows.get(1).rowNumber()).isEqualTo(3);assertThat(rows.get(2).userId()).doesNotMatch("[0-9]+");assertThat(rows.get(3).userId()).doesNotMatch("[0-9]+");
 }
 @Test void acceptsSafeIntegralNumericDecimalAndExponentNotation()throws Exception{
   var file=workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");s.createRow(1).createCell(0).setCellValue(123d);s.createRow(2).createCell(0).setCellValue(123000000000000d);s.createRow(3).createCell(0).setCellValue(123.5d);s.createRow(4).createCell(0).setCellValue(0d);});
   var rows=parse(file);assertThat(rows.get(0).userId()).isEqualTo("123");assertThat(rows.get(1).userId()).isEqualTo("123000000000000");assertThat(rows.get(2).userId()).doesNotMatch("[0-9]+");assertThat(rows.get(3).userId()).doesNotMatch("[0-9]+");
 }
 @Test void boundsExtremeNumericExponentsBeforeIntegerExpansion()throws Exception{
   var original=workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");s.createRow(1).createCell(0).setCellValue(123d);});
   for(String raw:new String[]{"1E2147483647","1E-2147483647","100E2147483647","1.5E1"}){
     var out=new ByteArrayOutputStream();
     try(var in=new java.util.zip.ZipInputStream(original.getInputStream());var zip=new java.util.zip.ZipOutputStream(out)){
       java.util.zip.ZipEntry entry;while((entry=in.getNextEntry())!=null){byte[] bytes=in.readAllBytes();if(entry.getName().equals("xl/worksheets/sheet1.xml")){String xml=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);assertThat(xml).contains("<v>123.0</v>");bytes=xml.replace("<v>123.0</v>","<v>"+raw+"</v>").getBytes(java.nio.charset.StandardCharsets.UTF_8);}zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));zip.write(bytes);zip.closeEntry();}
     }
     var file=new MockMultipartFile("file","users.xlsx","xlsx",out.toByteArray());
     org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(3),()->{String id=parse(file).get(0).userId();if(raw.equals("1.5E1"))assertThat(id).isEqualTo("15");else assertThat(id).doesNotMatch("[0-9]+");});
   }
 }
 @Test void rejectsBadHeadersAdditionalSheetsAndLateInvalidColumnBeforeReturningAnyRows()throws Exception {
   for(int variant=0;variant<3;variant++){final int v=variant;var file=workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue(v==0?"phone":"userId");s.createRow(1).createCell(0).setCellValue("1");if(v==1)w.createSheet();if(v==2)s.createRow(2).createCell(1).setCellValue("late malformed");});assertThatThrownBy(()->parse(file)).isInstanceOf(Exception.class);}
 }
 @Test void rejectsCellTextAndCompressedFileBudget()throws Exception {
   var file=workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");s.createRow(1).createCell(0).setCellValue("9".repeat(129));});assertThatThrownBy(()->parse(file)).isInstanceOf(Exception.class);
   assertThatThrownBy(()->parse(new MockMultipartFile("file","users.xlsx","x",new byte[5*1024*1024+1]))).isInstanceOf(IOException.class);
 }
 @Test void refusesMacrosDisguisedAsXlsx()throws Exception {
   var file=workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");s.createRow(1).createCell(0).setCellValue("1");w.setWorkbookType(org.apache.poi.xssf.usermodel.XSSFWorkbookType.XLSM);});
   assertThatThrownBy(()->parse(file)).isInstanceOf(Exception.class);
 }
 @Test void increasingWorkbooksParseUnderNinetySixMiBHeap()throws Exception{
   for(int rows:new int[]{100,1000,10000}){
     var log=java.nio.file.Path.of("target/batch-memory-"+rows+".log");
     String executable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
     var child=new ProcessBuilder(executable,"-Xmx96m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),VoucherBatchMemoryWorker.class.getName(),Integer.toString(rows)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
     try{assertThat(child.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)).as("child %s",log).isTrue();assertThat(child.exitValue()).as(java.nio.file.Files.readString(log)).isZero();}finally{if(child.isAlive())child.destroyForcibly();}
   }
 }
}
