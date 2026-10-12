/* WebP decoder integration point for real Win1.04 codec work.
 * The VP8 / VP8L bitstreams are substantially more complex than
 * PNG DEFLATE and baseline JPEG. This function MUST NOT return
 * success until actual pixels have been decompressed in the guest.
 */
#include "codecs.h"
int cv_decode_webp(HFILE file) {
    (void)file;
    return 0;
}
