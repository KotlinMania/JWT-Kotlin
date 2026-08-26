import JWTKMP
#if canImport(Testing)
import Testing

@Suite struct JWTKMPExportTests {
    @Test func testSwiftModuleLoads() throws {
        #expect(Bool(true))
    }
}
#else
import XCTest

final class JWTKMPExportTests: XCTestCase {
    func testSwiftModuleLoads() throws {
        XCTAssertTrue(true)
    }
}
#endif
