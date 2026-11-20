import JWTKMP
import Testing

@Suite("JWTKMP Swift Export Tests")
struct JWTKMPExportTests {
    @Test func testSwiftModuleLoads() throws {
        #expect(Bool(true), "JWTKMP swift module imported cleanly")
    }

    @Test func testHs256SignDecodeAndVerify() throws {
        let alg = algorithms.Algorithm.Companion.shared.hmac256(secret: "secret")
        let token = try JWT.shared
            .create()
            .withIssuer(issuer: "auth0")
            .withSubject(subject: "user-123")
            .withAudience(audience: "api")
            .withClaim(name: "role", value: "admin")
            .sign(algorithm: alg)

        let decoded = try JWT.shared.decode(token: token)
        #expect(decoded.issuer == "auth0")
        #expect(decoded.subject == "user-123")
        #expect(decoded.algorithm == "HS256")
        #expect(decoded.getClaim(name: "role").asString() == "admin")

        let verified = try JWT.shared
            .require(algorithm: alg)
            .withIssuer(issuer: "auth0", "backup")
            .withSubject(subject: "user-123")
            .withAudience(audience: "api")
            .withClaim(name: "role", value: "admin")
            .build()
            .verify(token: token)
        #expect(verified.issuer == "auth0")
        #expect(verified.subject == "user-123")
    }

    @Test func testHs384AndHs512RoundTrip() throws {
        let alg384 = algorithms.Algorithm.Companion.shared.hmac384(secret: "secret-384")
        let token384 = try JWT.shared
            .create()
            .withIssuer(issuer: "issuer-384")
            .sign(algorithm: alg384)
        let verified384 = try JWT.shared
            .require(algorithm: alg384)
            .withIssuer(issuer: "issuer-384" as String?)
            .build()
            .verify(token: token384)
        #expect(verified384.algorithm == "HS384")
        #expect(verified384.issuer == "issuer-384")

        let alg512 = algorithms.Algorithm.Companion.shared.hmac512(secret: "secret-512")
        let token512 = try JWT.shared
            .create()
            .withIssuer(issuer: "issuer-512")
            .sign(algorithm: alg512)
        let verified512 = try JWT.shared
            .require(algorithm: alg512)
            .withIssuer(issuer: "issuer-512" as String?)
            .build()
            .verify(token: token512)
        #expect(verified512.algorithm == "HS512")
        #expect(verified512.issuer == "issuer-512")
    }

    @Test func testInvalidSignatureThrows() throws {
        let signAlg = algorithms.Algorithm.Companion.shared.hmac256(secret: "secret")
        let wrongAlg = algorithms.Algorithm.Companion.shared.hmac256(secret: "wrong-secret")
        let token = try JWT.shared
            .create()
            .withIssuer(issuer: "auth0")
            .sign(algorithm: signAlg)

        #expect(throws: (any Error).self) {
            _ = try JWT.shared.require(algorithm: wrongAlg).build().verify(token: token)
        }
    }
}
