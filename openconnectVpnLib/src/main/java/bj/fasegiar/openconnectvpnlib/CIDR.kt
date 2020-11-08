package bj.fasegiar.openconnectvpnlib

internal class CIDR {
    var hostIp: String
        private set
    var maskLength: Int
        private set

    constructor(
        ip: String,
        mask: String
    ) {
        hostIp = ip
        maskLength = maskToLength(mask)
    }

    constructor(combo: String) {
        val comboPart = combo.split('/')
        val targetMaskOrMaskLength = comboPart[1]
        maskLength = (if (targetMaskOrMaskLength.matches("^[0-9]+$".toRegex())) {
            targetMaskOrMaskLength.toInt()
        } else {
            maskToLength(targetMaskOrMaskLength)
        }).takeIf { it in 0..32 } ?: 32
        hostIp = normalizeIp(comboPart[0], maskLength)
    }

    private fun normalizeIp(ip: String, theMaskLength: Int): String {
        val convertedIp = addressToInteger(ip)
        val normalizedIp = convertedIp and (0xffffffffL shl (32 - theMaskLength))
        return ip.takeIf { convertedIp == normalizedIp }
            ?: "${(normalizedIp and 0xff000000L) shr 24}.${(normalizedIp and 0xff0000L) shr 16}.${(normalizedIp and 0xff00) shr 8}.${normalizedIp and 0xff}"
    }

    private fun addressToInteger(mask: String): Long {
        val maskPart = mask.split("\\.".toRegex())
        return (maskPart[0].toLong() shl 24) +
                (maskPart[1].toLong() shl 16) +
                (maskPart[2].toInt() shl 8) +
                maskPart[3].toInt()
    }

    private fun maskToLength(mask: String): Int {
        var convertedMask = addressToInteger(mask)
        convertedMask += 1L shl 32
        var leadingZeroSequenceLength = 0
        while (convertedMask and 0x1 == 0L) {
            leadingZeroSequenceLength++
            convertedMask = convertedMask shr 1
        }
        return if (convertedMask != (0x1ffffffffL shr leadingZeroSequenceLength)) {
            32
        } else {
            32 - leadingZeroSequenceLength
        }
    }
}