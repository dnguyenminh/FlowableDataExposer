📄 TÀI LIỆU ĐẶC TẢ HỆ THỐNG: CASE DATA STORE & DYNAMIC INDEXER
I. KIẾN TRÚC PHÂN CẤP LỚP (CLASS HIERARCHY)
Mọi đối tượng dữ liệu trong hệ thống phải tuân thủ cấu trúc kế thừa sau để đảm bảo tính nhất quán trong việc trích xuất Metadata.
1. Lớp Tổ tiên: FlowableObject
Chứa các thuộc tính định danh và dấu vết kiểm toán (Audit Trails) dùng chung.
•	className: Định danh lớp Metadata (ví dụ: com.app.OrderCase).
•	createTime: Ngày giờ khởi tạo Instance (lấy từ startTime của Flowable).
•	startUserId: ID người dùng khởi tạo đối tượng (lấy từ startUserId của Flowable).
•	lastUpdated: Ngày giờ cập nhật bản ghi Snapshot hiện tại.
•	lastUpdateUserId: ID người dùng thực hiện cập nhật (lấy từ Authentication.getAuthenticatedUserId()).
•	tenantId: Định danh tổ chức/khách hàng.
2. Lớp Nghiệp vụ: WorkObject (Kế thừa FlowableObject)
Dành cho các Case Instance (CMMN).
•	caseInstanceId: UUID duy nhất của Case trong Flowable.
•	businessKey: Mã định danh nghiệp vụ của hồ sơ.
•	state: Trạng thái hiện tại của Case (Active, Completed, Terminated).
3. Lớp Quy trình: ProcessObject (Kế thừa FlowableObject)
Dành cho các Process Instance (BPMN).
•	processInstanceId: UUID duy nhất của quy trình.
•	processDefinitionId: ID của bản thiết kế quy trình.
•	parentInstanceId: ID của Case hoặc Process cha đã kích hoạt quy trình này.
4. Lớp Dữ liệu: DataObject (Kế thừa FlowableObject)
Dành cho các đối tượng dữ liệu dùng chung hoặc các Object lồng bên trong.
________________________________________
II. DANH SÁCH USE CASES NGHIỆP VỤ
UC-01: Chụp Snapshot và Lưu trữ vĩnh viễn (Source of Truth)
•	Mô tả: Khi quy trình đạt đến điểm lưu trữ, hệ thống chụp lại toàn bộ biến dữ liệu cùng với các thuộc tính của FlowableObject.
•	Luồng xử lý:
1.	Interceptor bắt sự kiện từ casePersistDelegate.
2.	Hệ thống tự động điền các trường Audit (lastUpdated, lastUpdateUserId) từ Engine context.
3.	Mã hóa JSON Payload bằng AES-256-GCM (Envelope Encryption).
4.	Lưu vĩnh viễn vào bảng sys_case_data_store. Lưu ý: Bảng này không bao giờ bị xóa.
UC-02: Trích xuất thuộc tính động (Exposing and Indexing Properties)
•	Mô tả: Tự động đồng bộ dữ liệu từ JSON Blob ra các bảng dữ liệu có cấu trúc (structured data tables) để phục vụ truy vấn và báo cáo. Quá trình này có hai hình thức:
    - **Exposing Properties:** "Thúc đẩy" (promote) các thuộc tính quan trọng từ JSON vào các cột trên chính bảng nghiệp vụ (work table).
    - **Indexing Properties:** Trích xuất thuộc tính ra các bảng chỉ mục (index tables) chuyên dụng, tách biệt hoàn toàn cho mục đích báo cáo.
•	Cơ chế:
o	Sử dụng Virtual Threads để giải mã và trích xuất bất đồng bộ.
o	Hỗ trợ JsonPath cho dữ liệu phức tạp: $.items[0] (List) hoặc $.params['region'] (Map).
o	Tự động trích xuất các thuộc tính của lớp cha (FlowableObject) ra các cột CREATED_AT, UPDATED_BY, v.v.
UC-03: Quản lý Metadata, Kế thừa và Mixins (QUY TẮC RÕ RÀNG)
•	Mục tiêu: đảm bảo việc giải quyết mapping là xác định, có thể truy vết và an toàn cho reindex.
•	Nguyên tắc ưu tiên (deterministic precedence):
	1) **Child (class) — cao nhất**: mapping hoặc `remove` khai báo trực tiếp trên class con luôn thắng.
	2) **Mixins — áp dụng theo thứ tự khai báo (left → right); _mixin cuối cùng thắng_**. **Trong xung đột giữa mixin và parent, mapping của mixin được ưu tiên**.
	3) **Parent chain — áp dụng bottom‑up (nearest parent overrides distant parent)** (chỉ áp dụng khi mixin/child không định nghĩa trường đó).
	4) **Nguồn dữ liệu**: DB-backed (latest enabled) > file-backed canonical.

	Ví dụ ngắn: `Order` (parent=WorkObject) + `mixins: [A,B]` và A/B định nghĩa `shared_col` → kết quả: `shared_col` từ **B**; nếu `Order` định nghĩa `shared_col` thì `Order` thắng.

	CI expectation: any conflict where merged `plainColumn` types differ or a circular parent/mixin is detected must produce a diagnostic; strict CI mode should fail the build.
•	Hành vi đặc biệt:
	- `remove:true` được xử lý như mapping: nếu một cấp thấp hơn gọi `remove`, cấp cao hơn vẫn có thể tái‑khai báo cùng cột (child có thể tái‑thêm).
	- Kiểu dữ liệu cho cùng `plainColumn` phải tương thích; nếu không, resolver **bắt buộc** phải báo diagnostic (CI fail in strict mode).
	- Vòng lặp (circular parent/mixin) phải được phát hiện và báo lỗi — không làm rơi vào đệ quy vô hạn.
•	Provenance & Diagnostics (bắt buộc):
	- Mỗi trường được resolve phải kèm provenance: sourceClass, sourceKind(file|db), sourceModule, sourceLocation.
	- UI/Field‑Check phải hiển thị provenance để BA/Dev dễ debug.
•	Ví dụ ngắn:
	- `Order` (parent=WorkObject) + mixins [A,B] + child mapping `order_total` => precedence: child > B > A > WorkObject.
	- Nếu `A` và `B` đều định nghĩa `shared_col`, B (khai báo sau) thắng, trừ khi child override.
•	Kiểm thử tối thiểu yêu cầu trong module core:
	- Unit tests cho: child override, mixin order (last wins), remove semantics, cycle detection, type‑conflict detection.
	- Integration: end‑to‑end test (sample blob → CaseDataWorker → case_plain_* column populated from mixin/parent).
	- **Auto-DDL behaviour**: unit tests that generate DDL from metadata (idempotent), apply it to an ephemeral schema (H2) and assert the column exists and is writable; an integration test that (1) generates/apply DDL for a requested plainColumn, (2) runs the worker, and (3) verifies the new column is populated.  
	  - Production note: automatic DDL generation is supported for developer convenience and for generating vetted migration SQL; actual schema changes in production MUST be applied via DB migrations / DBA review and the framework will emit migration SQL and a backfill plan (reindex) for review.
•	CI / Lint rules (recommended):
	- Fail build on circular parent/mixin, incompatible `plainColumn` types across merged metadata, or core exposing non‑core domain classes.
	- Emit warnings for any cross‑module duplicate definitions and include provenance in the report.
UC-04: Tái cấu trúc chỉ mục (Full Re-indexing)
•	Mô tả: Khi thêm cột mới vào báo cáo, hệ thống quét lại sys_case_data_store, giải mã dữ liệu lịch sử và cập nhật vào bảng Index.
•	Ý nghĩa: Đảm bảo bảng Index có thể bị xóa và tái tạo bất cứ lúc nào từ "nguồn sự thật" là Blob Store.
________________________________________
III. THIẾT KẾ GIAO DIỆN QUẢN LÝ (UI MOCKUP)
Hệ thống cung cấp một Console để BA quản lý Mapping:
1.	Màn hình Mapping: Cho phép chọn Class, nhập JsonPath và chọn cột đích trong Database.
2.	Nút "Check Path": Kiểm tra trực tiếp JsonPath trên một bản ghi thực tế từ Case Data Store.
3.	Màn hình Re-index: Theo dõi tiến trình đồng bộ lại dữ liệu lịch sử khi Metadata thay đổi.

