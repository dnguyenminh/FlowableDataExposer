1. Các thuộc tính mặc định trong Flowable 7.2.0
Trong Flowable, Case (CMMN) và Process (BPMN) có các tập hợp thuộc tính hệ thống riêng biệt nhưng có nhiều điểm tương đồng.

A. Đối với Case Instance (CMMN)
Các trường này giúp định danh và quản lý trạng thái vòng đời của Case:


id: Định danh duy nhất của Case Instance (UUID).


caseDefinitionId: ID của bản thiết kế (Template) được sử dụng.


businessKey: Mã nghiệp vụ do người dùng định nghĩa (ví dụ: số hồ sơ vay).


state: Trạng thái hiện tại của Case (e.g., active, completed, terminated).


startTime: Thời điểm khởi tạo Case.


startUserId: Người thực hiện khởi tạo Case.


tenantId: Định danh đơn vị/tổ chức (dùng cho kiến trúc đa khách hàng).

B. Đối với Process Instance (BPMN)
Tương tự như Case nhưng tập trung vào luồng thực thi tuyến tính:


id: Định danh duy nhất của Process Instance.


processDefinitionId: ID của bản vẽ quy trình.


businessKey: Mã nghiệp vụ của quy trình.


startTime: Thời điểm bắt đầu quy trình.


startUserId: Người kích hoạt quy trình.


parentId: ID của Case hoặc Process cha (nếu quy trình này được gọi từ quy trình khác).

2. Thiết kế Lớp Cơ sở (System Base Class)
Dựa trên yêu cầu xây dựng hệ thống giống Pega, lớp cơ sở cao nhất của bạn nên bao gồm các "System Fields" để phục vụ việc truy vấn, bảo mật và quản lý vòng đời dữ liệu.

Đề xuất Lớp: Work-Base (Lớp tổ tiên)
Lớp này sẽ chứa các metadata mà mọi Case con đều phải thừa hưởng để Expose ra các bảng Index.