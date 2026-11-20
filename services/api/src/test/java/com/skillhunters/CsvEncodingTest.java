package com.skillhunters;
import com.skillhunters.exports.CsvExports;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CsvEncodingTest {
 @Test void formulasAreNeutralizedBehindUnicodeAndControls() {
  for(String value:java.util.List.of("=1+1","+SUM(A1:A2)","-1+1","@SUM(A1:A2)","\t\r=1","\ufeff =1","\u00a0+1","＝1+1","\u200b@x"))
   assertThat(CsvExports.cell(value)).startsWith("\"'").endsWith("\"").doesNotContain("\r","\n","\t");
 }
 @Test void quotesDelimitersAndMultilineInputCannotEscapeOneCell() {
  assertThat(CsvExports.cell("Équipe, \"Java\"\r\n=1")).isEqualTo("\"Équipe, \"\"Java\"\"  =1\"");
  assertThat(CsvExports.cell(null)).isEqualTo("\"\"");
 }
}
