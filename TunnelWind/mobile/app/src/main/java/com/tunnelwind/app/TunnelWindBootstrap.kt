/**
 * Tunnel Wind Android — bootstrap engine constants.
 * Mirrors the Windows client's recovered public distribution anchors (see the
 * ANALYSIS.md document in the parent repo). Values are public network constants;
 * no private keys / server secrets are committed here.
 */
object TunnelWindBootstrap {
    const val sponsorId: String = "1BC527D3D09985CF"
    const val propagationChannelId: String = "92AACC5BABE0944C"
    const val clientPlatform: String = "android"
    const val coreConfigVersion: String = "jodorowsky-1"

    val remoteServerListUrls: List<String> = listOf(
        "https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/server_list_compressed",
        "https://www.raceeggxdas.com/web/mjr4-p23r-puwl/server_list_compressed",
        "https://www.blogsfmcancercitizen.com/web/mjr4-p23r-puwl/server_list_compressed",
        "https://www.sysstemafariuniversitydebt.com/web/mjr4-p23r-puwl/server_list_compressed",
    )
    val obfuscatedServerListRootUrls: List<String> = listOf(
        "https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/osl",
        "https://www.raceeggxdas.com/web/mjr4-p23r-puwl/osl",
        "https://www.blogsfmcancercitizen.com/web/mjr4-p23r-puwl/osl",
        "https://www.sysstemafariuniversitydebt.com/web/mjr4-p23r-puwl/osl",
        "https://www.lockglobeaccountingcl.com.global.prod.fastly.net/",
        "https://www.nwdbugugnewslattergeteway.com.global.prod.fastly.net/",
        "https://www.feedswhichbargainfix.com.global.prod.fastly.net/",
    )
    val feedbackUploadUrls: List<String> = listOf("https://s3.amazonaws.com/psiupload/")
    // anchored public keys
    const val remoteServerListSignaturePublicKey: String =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
    const val serverEntrySignaturePublicKey: String =
        "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="
    const val appVersion: String = "1.2.0"
}
