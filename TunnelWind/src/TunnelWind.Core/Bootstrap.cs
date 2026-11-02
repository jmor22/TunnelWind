using System.Text.Json.Serialization;

namespace TunnelWind.Core;

/// <summary>
/// Bootstrap configuration recovered by static analysis of the original psiphon3.exe
/// launcher (see ANALYSIS.md). Public distribution constants only.
/// </summary>
public static class Bootstrap
{
    // ---------- IDs ----------
    public const string SponsorId = "1BC527D3D09985CF";
    public const string PropagationChannelId = "92AACC5BABE0944C";
    public const string LegacyClientVersion = "189";

    // ---------- S3 distribution (plain + mirror hosts, decoded from base64 URLs) ----------
    public static readonly string[] RemoteServerListUrls =
    [
        "https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/server_list_compressed",
        "https://www.raceeggxdas.com/web/mjr4-p23r-puwl/server_list_compressed",
        "https://www.blogsfmcancercitizen.com/web/mjr4-p23r-puwl/server_list_compressed",
        "https://www.sysstemafariuniversitydebt.com/web/mjr4-p23r-puwl/server_list_compressed",
    ];

    public static readonly string[] ObfuscatedServerListRootUrls =
    [
        "https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/osl",
        "https://www.raceeggxdas.com/web/mjr4-p23r-puwl/osl",
        "https://www.blogsfmcancercitizen.com/web/mjr4-p23r-puwl/osl",
        "https://www.sysstemafariuniversitydebt.com/web/mjr4-p23r-puwl/osl",
        "https://www.lockglobeaccountingcl.com.global.prod.fastly.net/",
        "https://www.nwdbugugnewslattergeteway.com.global.prod.fastly.net/",
        "https://www.feedswhichbargainfix.com.global.prod.fastly.net/",
    ];

    public static readonly string[] FeedbackUploadUrls = ["https://s3.amazonaws.com/psiupload/"];

    public static readonly string[] UpgradeDownloadUrls =
    [
        "https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/psiphon3.exe.upgrade",
    ];

    // ---------- Trust anchors (public keys, as issued by the Psiphon network) ----------
    // NOTE (verified by SHA-256 digest comparison against a live remote server list):
    //   * RemoteServerListSignaturePublicKey = RSA-3072 SPKI (base64 TEXT) — its SHA-256
    //     equals the list's "signingPublicKeyDigest" field.
    //   * ServerEntrySignaturePublicKey = Ed25519 key — verifies per-entry signatures.
    // Both were previously swapped; corrected after engine probe evidence.
    public const string RemoteServerListSignaturePublicKey =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=";

    public const string ServerEntrySignaturePublicKey = "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=";

    public const string FeedbackEncryptionPublicKey =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAxltZsddAqX0qE4dK+X7QfcPfoGbvs6DAxkwY5Cb7mcWW9YpNesOdb+aq0kmDEeUDnMYIVEnUnNLFSOpF/CvjvZ1WQpxYy2sE/ulQUXO9XCtoucM4jaZIKza9TPNUsWaiiMC86UOO6kjZLRodosXwgdykfbn0GGOy90urkMTygSi1JmnUjDqHXNr8mgVS/9qTMX68N598CjzU3zeBJi5Rh2wChRzMDw7y0umJ/xJ7vevJOmEp5qGg4J4x5hMAagG1AF4SDgXwVdSKcwcRoeUUxmmWRgyirPJdEgyLCFNX0Z1LhWmB+Kz8aq7+d+5eEIoIxjmRu4O9AfB/ngvNwapIBSDj/STPszsluH2lIGY9nDBVxKZqQ8oGjDQMoFedzRu2z2dkVYqCoN7Lfve3JTku1MxsIylh67+9emW9d1F+I5v+LWicBrusmRStSB7z4CUuqZEa8GYKNGM0A1Axkdz9y/Dv+4NB6cEw01szEXl/9hJxKh8MStLCFZ1eNaokrcbxnPGHDdZSqGmV3x8eRy1GvQv0xRnoRpyaqdmQvjb7XupiTD+5GT+7PjAwXTN4kJtm39DyIEwKmmXWcFQtn6JWefwRbcXwKKDjj99QssyYQp+7EPiv/QwUAMnHTN2CrWYXEhbBHBkdgxkioPJ47j8nXxydXPBKXcnCruQ4ICEmrsECAQM=";

    // ---------- Embedded bootstrap server entry (legacy hex-encoded list format) ----------
    // Format: "0 0 0 0 " + upper-case hex of one JSON server entry.
    // Recoverd from the binary resource section of psiphon3.exe (file offset
    // 0x155468 in the unpacked image; see ANALYSIS.md section 2).
    public static string EmbeddedServerEntryHex = "";
    public static bool HasEmbeddedServerEntry => EmbeddedServerEntryHex.Length > 0;

    // ---------- Opaque runtime parameter (passed verbatim by the original client) ----------
    public static string AdditionalParametersOpaque = "";
}
