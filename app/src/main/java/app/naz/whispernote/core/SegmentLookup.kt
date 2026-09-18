package app.naz.whispernote.core

/** Transcript segments are ordered by start time. Gaps do not highlight a segment. */
fun segmentAtPosition(segments: List<Segment>, position: Long): Int {
    var low=0; var high=segments.lastIndex; var candidate=-1
    while(low<=high) {
        val middle=(low+high).ushr(1)
        if(segments[middle].start<=position) {candidate=middle;low=middle+1} else high=middle-1
    }
    return candidate.takeIf { it>=0 && position<segments[it].end } ?: -1
}
