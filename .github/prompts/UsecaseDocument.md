🚀 BUSINESS USE CASE SPECIFICATION: CASE DATA STORE & DYNAMIC INDEXER
1. TẦM NHÌN NGHIỆP VỤ (BUSINESS VISION)
Trong các hệ thống BPM hiện đại, dữ liệu không được "đóng đinh" vào các bảng cố định.

Vấn đề: Khi quy trình thay đổi, việc thêm cột vào Database truyền thống rất tốn kém và dễ gây lỗi hệ thống.

Giải pháp: Lưu trữ toàn bộ trạng thái Case vào một Case Data Store (Blob) vĩnh viễn. Sau đó, trích xuất (Expose) các thuộc tính cần thiết ra các cấu trúc dữ liệu phẳng để phục vụ báo cáo, bao gồm việc "thúc đẩy" (promoting) dữ liệu ra các cột trên bảng nghiệp vụ chính hoặc đưa dữ liệu vào các bảng chỉ mục (Index Tables) chuyên dụng. Nếu báo cáo cần thêm thông tin, ta chỉ việc "Re-index" từ nguồn dữ liệu gốc (Blob).

2. DANH SÁCH USE CASES
UC 01: Lưu trữ Case Data vĩnh viễn (The Source of Truth)
Actor: Flowable Engine (System).

Mô tả: Khi bất kỳ Task nào trong Case (CMMN) hoặc Process (BPMN) hoàn thành, toàn bộ biến (variableMap) phải được chụp ảnh (Snapshot). Snapshots must include the canonical FlowableObject fields (createTime, startUserId, tenantId, etc.) so that parent-class audit columns can be populated in Index Tables.

Luồng xử lý:

1. Interceptor bắt sự kiện commit của Flowable.
2. CasePersistDelegate sẽ best-effort bổ sung các thuộc tính Flowable (createTime, startUserId, processDefinitionId, businessKey, tenantId) từ execution/engine vào variableMap trước khi persist.
3. Sinh DataKey ngẫu nhiên, mã hóa JSON Payload bằng AES-256-GCM.
4. Mã hóa DataKey bằng MasterKey (Envelope Encryption).
5. Lưu bản ghi vào bảng sys_case_data_store (Vĩnh viễn). Bảng này là immutable.

UC 02: Trích xuất thuộc tính động (Property Exposure)
Actor: Async Indexer Worker (System).

Mô tả: Sau khi dữ liệu gốc được lưu, hệ thống tự động cập nhật các bảng dữ liệu dẫn xuất (derived data tables) để phục vụ báo cáo. Quá trình này có thể là "Exposing" (cập nhật cột trên bảng nghiệp vụ chính) hoặc "Indexing" (cập nhật các bảng chỉ mục chuyên dụng). Indexing must also expose parent-class audit fields (CREATED_AT, UPDATED_BY, TENANT_ID) to support cross-cutting reports.

Luồng xử lý:

1. Worker sử dụng Virtual Threads để giải mã Blob từ sys_case_data_store.
2. Truy vấn Metadata Mapping — **quy tắc precedence rõ ràng** (child/mixins/parent) và phải có diagnostics:
   - **Precedence:** child (class) > mixins (theo thứ tự khai báo — mixin cuối cùng thắng) > parent chain (near → far).
   - **Remove:** `remove:true` tuân theo cùng quy tắc; một cấp cao hơn vẫn có thể tái‑khai báo cột đã bị xoá.
   - **Type conflicts:** nếu cùng `plainColumn` có kiểu khác nhau, resolver phải báo diagnostic; CI strict mode nên fail.
   - **Cycles:** vòng lặp trong `parent`/`mixins` phải bị phát hiện và báo lỗi; resolver không đệ quy vô hạn.
   - **Provenance:** mỗi FieldMapping resolved phải kèm sourceClass + sourceKind(file|db) + sourceModule.
3. Sử dụng JsonPath để trích xuất:
   - Field đơn ($.amount)
   - Array/List theo index ($.approvers[0].name)
   - Map theo key ($.meta['priority'])
   - Parent audit fields: $.createTime, $.startUserId, $.tenantId (fallbacks from DB created_at/requested_by)
4. Thực hiện lệnh UPSERT vào bảng đích tương ứng (bảng nghiệp vụ chính hoặc bảng chỉ mục chuyên dụng, ví dụ: `idx_credit_card_report`).

UC 03: Định nghĩa Mapping, Kế thừa và Mixins (Metadata Management)
Actor: AI/Java Developer hoặc Business Analyst (BA).

Mô tả: Cấu hình cách dữ liệu được trích xuất từ Blob ra các bảng đích (work table hoặc index tables) qua giao diện UI. Metadata must be deterministic, auditable and safe.

Quy tắc nghiệp vụ (ngắn gọn):
- **Khai báo mixins:** cho phép tái sử dụng mapping (mixins áp dụng theo thứ tự khai báo; mixin sau cùng thắng khi có xung đột).
- **Kế thừa:** child tự động kế thừa mappings của parent; nearest parent overrides distant parent.
- **Ghi đè / remove:** child có thể override hoặc remove; precedence rule xác định kết quả cuối cùng.

Yêu cầu kiểm thử (bắt buộc): unit tests cho precedence, mixin order, remove semantics, type conflict detection; 1 E2E test (blob → CaseDataWorker → case_plain_*).



UC 04: Tái cấu trúc báo cáo (Re-indexing)
Actor: Admin/Business Analyst.

Mô tả: Khi cần thêm một cột mới vào bảng báo cáo cho cả các dữ liệu đã cũ.

Luồng xử lý:

Người dùng thêm Mapping mới vào Metadata.

Người dùng nhấn nút "Re-index All" cho Class đó.

Hệ thống quét lại toàn bộ bảng sys_case_data_store theo entity_type.

Giải mã, trích xuất theo Metadata mới và cập nhật lại bảng Index.

Ý nghĩa: Đảm bảo dữ liệu 5 năm trước vẫn có thể hiển thị thông tin mới trong báo cáo mà không cần nhập lại liệu.

3. CẤU TRÚC GIAO DIỆN QUẢN LÝ (UI MOCKUP SPEC)
Hệ thống cung cấp một Management Console cho BA:

Màn hình Danh sách Case: Xem trạng thái đồng bộ giữa Case Data Store và các bảng dữ liệu dẫn xuất (derived data tables).

Màn hình Mapping: Giao diện kéo thả hoặc nhập JsonPath để map vào cột Database.

Field Check: Nút kiểm tra tính hợp lệ của JsonPath trên một bản ghi thực tế.

Màn hình Monitor: Theo dõi các Virtual Threads đang chạy re-index, xử lý lỗi khi giải mã hoặc mapping sai.

4. CÁC ĐIỀU KIỆN RÀNG BUỘC (CONSTRAINTS)
Data Integrity: Bảng Case Data Store là bất biến (Immutable), không bao giờ được xóa.

Scalability: Việc trích xuất không được làm chậm luồng nghiệp vụ của người dùng (phải chạy Async).

Security: Master Key không bao giờ được lưu trong DB. Mọi hành động giải mã phải có log.

Performance: Sử dụng Caffeine Cache để lưu Metadata, tránh truy vấn DB Mapping hàng triệu lần.