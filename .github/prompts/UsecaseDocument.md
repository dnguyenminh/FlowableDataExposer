🚀 BUSINESS USE CASE SPECIFICATION: CASE DATA STORE & DYNAMIC INDEXER
1. TẦM NHÌN NGHIỆP VỤ (BUSINESS VISION)
Trong các hệ thống BPM hiện đại, dữ liệu không được "đóng đinh" vào các bảng cố định.

Vấn đề: Khi quy trình thay đổi, việc thêm cột vào Database truyền thống rất tốn kém và dễ gây lỗi hệ thống.

Giải pháp: Lưu trữ toàn bộ trạng thái Case vào một Case Data Store (Blob) vĩnh viễn và chỉ trích xuất (Expose) các thuộc tính cần thiết ra các Index Tables phục vụ báo cáo. Nếu báo cáo cần thêm thông tin, ta chỉ việc "Re-index" từ nguồn dữ liệu gốc (Blob).

2. DANH SÁCH USE CASES
UC 01: Lưu trữ Case Data vĩnh viễn (The Source of Truth)
Actor: Flowable Engine (System).

Mô tả: Khi bất kỳ Task nào trong Case (CMMN) hoặc Process (BPMN) hoàn thành, toàn bộ biến (variableMap) phải được chụp ảnh (Snapshot).

Luồng xử lý:

Interceptor bắt sự kiện commit của Flowable.

Xác định Class nghiệp vụ (ví dụ: com.bank.CreditCardApplication).

Sinh DataKey ngẫu nhiên, mã hóa JSON Payload bằng AES-256-GCM.

Mã hóa DataKey bằng MasterKey (Envelope Encryption).

Lưu bản ghi vào bảng sys_case_data_store (Vĩnh viễn).

UC 02: Trích xuất thuộc tính động (Property Exposure)
Actor: Async Indexer Worker (System).

Mô tả: Sau khi dữ liệu gốc được lưu, hệ thống tự động cập nhật bảng Index để phục vụ báo cáo.

Luồng xử lý:

Worker sử dụng Virtual Threads để giải mã Blob từ sys_case_data_store.

Truy vấn Metadata Mapping (bao gồm cả kế thừa từ lớp cha).

Sử dụng JsonPath để trích xuất:

Field đơn ($.amount).

Array/List theo index ($.approvers[0].name).

Map theo key ($.meta['priority']).

Thực hiện lệnh UPSERT vào bảng Index tương ứng (ví dụ: idx_credit_card_report).

UC 03: Định nghĩa Mapping & Kế thừa (Metadata Management)
Actor: AI/Java Developer hoặc Business Analyst (BA).

Mô tả: Cấu hình cách dữ liệu được trích xuất từ Blob ra bảng Index qua giao diện UI.

Quy tắc nghiệp vụ:

Inheritance: Nếu VIPLoan kế thừa BaseLoan, khi trích xuất VIPLoan, hệ thống phải tự động lấy cả các mapping của BaseLoan.

Override: Nếu lớp con định nghĩa lại cùng một column_name, giá trị của lớp con sẽ đè lên lớp cha.

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

Màn hình Danh sách Case: Xem trạng thái đồng bộ giữa Case Data Store và các Index Tables.

Màn hình Mapping: Giao diện kéo thả hoặc nhập JsonPath để map vào cột Database.

Field Check: Nút kiểm tra tính hợp lệ của JsonPath trên một bản ghi thực tế.

Màn hình Monitor: Theo dõi các Virtual Threads đang chạy re-index, xử lý lỗi khi giải mã hoặc mapping sai.

4. CÁC ĐIỀU KIỆN RÀNG BUỘC (CONSTRAINTS)
Data Integrity: Bảng Case Data Store là bất biến (Immutable), không bao giờ được xóa.

Scalability: Việc trích xuất không được làm chậm luồng nghiệp vụ của người dùng (phải chạy Async).

Security: Master Key không bao giờ được lưu trong DB. Mọi hành động giải mã phải có log.

Performance: Sử dụng Caffeine Cache để lưu Metadata, tránh truy vấn DB Mapping hàng triệu lần.