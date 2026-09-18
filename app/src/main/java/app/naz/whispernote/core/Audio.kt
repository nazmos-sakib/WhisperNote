package app.naz.whispernote.core

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.*
import java.nio.ByteOrder
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object Downloader {
    suspend fun download(url: String, target: File, progress: (Int) -> Unit) {
        require(URL(url).protocol == "https") { "Use a direct HTTPS audio URL." }
        val partial = File(target.path + ".part")
        var connection: HttpURLConnection? = null
        try {
            var next = URL(url)
            for (redirect in 0..5) {
                require(next.protocol == "https") { "Insecure redirects are not supported." }
                val c = (next.openConnection() as HttpURLConnection).apply { connectTimeout=20_000; readTimeout=30_000; instanceFollowRedirects=false }
                connection = c
                if(c.responseCode in 300..399) { val location=c.getHeaderField("Location") ?: error("Invalid redirect"); next=URL(next,location); c.disconnect() } else break
            }
            val c=connection ?: error("Connection failed")
            require(c.responseCode in 200..299) { "Download failed (HTTP ${c.responseCode})." }
            val total=c.contentLengthLong
            var count=0L; var last=-2
            c.inputStream.use { input -> partial.outputStream().use { out ->
                val buffer=ByteArray(65536)
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val n=input.read(buffer); if(n<0) break
                    count+=n; require(count <= 4L*1024*1024*1024) { "Download exceeds 4 GB." }
                    out.write(buffer,0,n)
                    val pct=if(total>0) (count*100/total).toInt().coerceIn(0,99) else -1
                    if(pct!=last) { progress(pct); last=pct }
                }
            } }
            require(count>0 && (total<=0 || count==total)) { "Incomplete download." }
            check(partial.renameTo(target)) { "Cannot save download." }
        } finally { connection?.disconnect(); partial.delete() }
    }
}

/** Streaming decoder and continuous linear resampler. PCM is spooled to disk, not a huge JVM array. */
class AudioDecoder(private val context: Context) {
    suspend fun decode(uri: Uri, output: File, startMs: Long = 0, endMs: Long = Long.MAX_VALUE): Long {
        require(startMs>=0 && endMs>startMs)
        val firstSample=startMs*16
        val lastSample=if(endMs==Long.MAX_VALUE) Long.MAX_VALUE else endMs*16
        val extractor=MediaExtractor(); var codec: MediaCodec?=null
        try {
            extractor.setDataSource(context,uri,null)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true } ?: error("This file contains no decodable audio.")
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec=decoder
            decoder.configure(format,null,null,0); decoder.start()
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=AudioFormat.ENCODING_PCM_16BIT
            var inputDone=false; var outputDone=false; var index=0L; var next=0.0; var previous=0f; var written=0L
            val info=MediaCodec.BufferInfo()
            BufferedOutputStream(output.outputStream()).use { out ->
                fun sample(v: Float) {
                    if(index==0L) previous=v
                    while(next<=index) {
                        val fraction=(next-(index-1)).coerceIn(0.0,1.0)
                        val value=(previous+(v-previous)*fraction).toFloat().coerceIn(-1f,1f)
                        val bits=java.lang.Float.floatToIntBits(value)
                        if(written>=firstSample && written<lastSample) { out.write(bits and 255); out.write(bits ushr 8 and 255); out.write(bits ushr 16 and 255); out.write(bits ushr 24 and 255) }
                        written++; next+=rate/16000.0
                    }
                    previous=v; index++
                }
                while(!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if(!inputDone) {
                        val i=decoder.dequeueInputBuffer(10000)
                        if(i>=0) {
                            val b=decoder.getInputBuffer(i)!!; val size=extractor.readSampleData(b,0)
                            if(size<0) { decoder.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true }
                            else { decoder.queueInputBuffer(i,0,size,extractor.sampleTime,0); extractor.advance() }
                        }
                    }
                    val i=decoder.dequeueOutputBuffer(info,10000)
                    if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f=decoder.outputFormat; rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding==AudioFormat.ENCODING_PCM_16BIT || encoding==AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoder PCM format." }
                    } else if(i>=0) {
                        val b=decoder.getOutputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                        b.position(info.offset); b.limit(info.offset+info.size)
                        val bytes=if(encoding==AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        while(b.remaining()>=channels*bytes && written<lastSample) {
                            var mono=0f
                            repeat(channels) { mono+=if(bytes==4) b.float else b.short/32768f }
                            sample(mono/channels)
                        }
                        outputDone=written>=lastSample || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(i,false)
                    }
                }
            }
            require(written>firstSample && (lastSample==Long.MAX_VALUE || written>=lastSample)) { "The audio does not contain the selected time range." }
            return (minOf(written,lastSample)-firstSample)*1000/16000
        } finally { codec?.release(); extractor.release() }
    }
}
