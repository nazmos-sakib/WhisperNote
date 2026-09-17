package app.naz.whispernote
import app.naz.whispernote.core.*
import org.junit.Assert.*
import org.junit.Test
class TranscriptTest {
    @Test fun timestampPreservesSrtPrecision() { assertEquals("01:04:32,019",timestamp(3872019,true)); assertEquals("01:04:32",timestamp(3872019)); assertEquals("00:00",timestamp(-1)) }
    @Test fun subtitleExportPreservesBoundaries() { val note=Note(title="Interview",segments=listOf(Segment(4012,9987," Guten Tag. "),Segment(10000,12555,"Hello."))); assertEquals("1\n00:00:04,012 --> 00:00:09,987\nGuten Tag.\n\n2\n00:00:10,000 --> 00:00:12,555\nHello.\n",Exporter.render(note,"srt")) }
    @Test fun markdownContainsTitleAndSegments() { val note=Note(title="Interview",segments=listOf(Segment(4000,6000,"Welcome"))); assertEquals("# Interview\n\n**00:04**\n\nWelcome",Exporter.render(note,"md")) }
}
