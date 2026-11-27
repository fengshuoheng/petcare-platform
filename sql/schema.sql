-- =============================================================
-- 宠物上门服务预约交易平台 petcare
-- MySQL 8.0
-- =============================================================
CREATE DATABASE IF NOT EXISTS petcare DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE petcare;

DROP TABLE IF EXISTS ai_message;
DROP TABLE IF EXISTS knowledge_chunk;
DROP TABLE IF EXISTS notification;
DROP TABLE IF EXISTS settlement;
DROP TABLE IF EXISTS complaint;
DROP TABLE IF EXISTS review;
DROP TABLE IF EXISTS service_record;
DROP TABLE IF EXISTS refund_order;
DROP TABLE IF EXISTS payment_order;
DROP TABLE IF EXISTS order_status_log;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS time_slot;
DROP TABLE IF EXISTS staff_schedule;
DROP TABLE IF EXISTS staff;
DROP TABLE IF EXISTS service_item;
DROP TABLE IF EXISTS address;
DROP TABLE IF EXISTS pet;
DROP TABLE IF EXISTS app_user;

CREATE TABLE app_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    phone       VARCHAR(20)  NOT NULL,
    nickname    VARCHAR(32)  DEFAULT NULL,
    avatar      VARCHAR(255) DEFAULT NULL,
    role        TINYINT      NOT NULL DEFAULT 0 COMMENT '0用户 1服务者 2管理员',
    staff_id    BIGINT       DEFAULT NULL,
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

CREATE TABLE pet (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    user_id           BIGINT      NOT NULL,
    name              VARCHAR(32) NOT NULL,
    species           VARCHAR(16) NOT NULL COMMENT 'DOG/CAT/OTHER',
    breed             VARCHAR(32) DEFAULT NULL,
    gender            TINYINT     DEFAULT 0 COMMENT '0未知 1公 2母',
    birthday          DATE        DEFAULT NULL,
    weight_kg         DECIMAL(5,2) DEFAULT NULL,
    sterilized        TINYINT     DEFAULT 0,
    vaccine_date      DATE        DEFAULT NULL,
    vaccine_next_date DATE        DEFAULT NULL,
    allergies         VARCHAR(255) DEFAULT NULL,
    traits            VARCHAR(255) DEFAULT NULL,
    notes             VARCHAR(500) DEFAULT NULL,
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宠物档案';

CREATE TABLE address (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    contact     VARCHAR(32)  NOT NULL,
    phone       VARCHAR(20)  NOT NULL,
    city        VARCHAR(32)  NOT NULL,
    district    VARCHAR(32)  DEFAULT NULL,
    detail      VARCHAR(255) NOT NULL,
    lat         DECIMAL(10,6) DEFAULT NULL,
    lng         DECIMAL(10,6) DEFAULT NULL,
    is_default  TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='地址';

CREATE TABLE service_item (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(64)  NOT NULL,
    category     VARCHAR(32)  NOT NULL COMMENT '洗护/美容/喂养/遛狗/寄养/陪诊',
    description  VARCHAR(500) DEFAULT NULL,
    duration_min INT          NOT NULL DEFAULT 60,
    price_s      DECIMAL(10,2) NOT NULL DEFAULT 0,
    price_m      DECIMAL(10,2) NOT NULL DEFAULT 0,
    price_l      DECIMAL(10,2) NOT NULL DEFAULT 0,
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
    sort         INT          NOT NULL DEFAULT 0,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务项目';

CREATE TABLE staff (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       DEFAULT NULL,
    name         VARCHAR(32)  NOT NULL,
    phone        VARCHAR(20)  NOT NULL,
    level        TINYINT      NOT NULL DEFAULT 1,
    rating       DECIMAL(3,2) NOT NULL DEFAULT 5.00,
    order_count  INT          NOT NULL DEFAULT 0,
    size_types   VARCHAR(16)  NOT NULL DEFAULT 'S,M,L' COMMENT '可服务体型',
    service_area VARCHAR(64)  DEFAULT NULL,
    lat          DECIMAL(10,6) DEFAULT NULL,
    lng          DECIMAL(10,6) DEFAULT NULL,
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1可接单 0休息 2忙碌 3封禁',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务者';

CREATE TABLE staff_schedule (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    staff_id   BIGINT   NOT NULL,
    weekday    TINYINT  NOT NULL COMMENT '1-7',
    start_time TIME     NOT NULL,
    end_time   TIME     NOT NULL,
    status     TINYINT  NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_staff (staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排班';

CREATE TABLE time_slot (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    staff_id       BIGINT   NOT NULL,
    slot_start     DATETIME NOT NULL,
    slot_end       DATETIME NOT NULL,
    status         TINYINT  NOT NULL DEFAULT 0 COMMENT '0可约 1锁定 2已约',
    order_id       BIGINT   DEFAULT NULL,
    lock_expire_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_staff_start (staff_id, slot_start),
    KEY idx_start_status (slot_start, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='时间槽';

CREATE TABLE orders (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_no        VARCHAR(32)  NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    staff_id        BIGINT       DEFAULT NULL,
    pet_id          BIGINT       NOT NULL,
    address_id      BIGINT       NOT NULL,
    service_id      BIGINT       NOT NULL,
    slot_id         BIGINT       NOT NULL,
    service_name    VARCHAR(64)  NOT NULL,
    pet_name        VARCHAR(32)  NOT NULL,
    pet_size        VARCHAR(4)   NOT NULL,
    address_snapshot VARCHAR(300) NOT NULL,
    contact_phone   VARCHAR(20)  NOT NULL,
    amount          DECIMAL(10,2) NOT NULL DEFAULT 0,
    addon_amount    DECIMAL(10,2) NOT NULL DEFAULT 0,
    distance_fee    DECIMAL(10,2) NOT NULL DEFAULT 0,
    night_fee       DECIMAL(10,2) NOT NULL DEFAULT 0,
    urgent_fee      DECIMAL(10,2) NOT NULL DEFAULT 0,
    pay_amount      DECIMAL(10,2) NOT NULL DEFAULT 0,
    addon_json      VARCHAR(500) DEFAULT NULL,
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0待支付 1待派单 2待接单 3待上门 4服务中 5待确认 6已完成 7已取消 8退款中 9已退款 10已关闭',
    urgent          TINYINT      NOT NULL DEFAULT 0,
    remark          VARCHAR(255) DEFAULT NULL,
    cancel_reason   VARCHAR(255) DEFAULT NULL,
    cancel_by       VARCHAR(16)  DEFAULT NULL,
    reschedule_count INT         NOT NULL DEFAULT 0,
    urge_accept     INT          NOT NULL DEFAULT 0,
    urge_visit      INT          NOT NULL DEFAULT 0,
    urge_finish     INT          NOT NULL DEFAULT 0,
    last_urge_time  DATETIME     DEFAULT NULL,
    slot_start      DATETIME     NOT NULL,
    slot_end        DATETIME     NOT NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pay_expire_at   DATETIME     DEFAULT NULL,
    pay_time        DATETIME     DEFAULT NULL,
    accept_time     DATETIME     DEFAULT NULL,
    depart_time     DATETIME     DEFAULT NULL,
    arrive_time     DATETIME     DEFAULT NULL,
    finish_time     DATETIME     DEFAULT NULL,
    confirm_time    DATETIME     DEFAULT NULL,
    close_time      DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_request (request_id),
    KEY idx_user (user_id),
    KEY idx_staff (staff_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

CREATE TABLE order_status_log (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_id      BIGINT      NOT NULL,
    from_status   TINYINT     NOT NULL,
    to_status     TINYINT     NOT NULL,
    operator_type VARCHAR(16) NOT NULL,
    operator_id   BIGINT      DEFAULT NULL,
    remark        VARCHAR(255) DEFAULT NULL,
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态流水';

CREATE TABLE payment_order (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    pay_no      VARCHAR(32)  NOT NULL,
    order_id    BIGINT       NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    channel     VARCHAR(16)  NOT NULL DEFAULT 'MOCK',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0初始化 1支付中 2成功 3失败 4关闭',
    pay_time    DATETIME     DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_no (pay_no),
    KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付单';

CREATE TABLE refund_order (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    refund_no   VARCHAR(32)  NOT NULL,
    order_id    BIGINT       NOT NULL,
    payment_id  BIGINT       DEFAULT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    reason      VARCHAR(255) DEFAULT NULL,
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0申请 1同意 2拒绝 3已退款',
    operator_id BIGINT       DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no (refund_no),
    KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款单';

CREATE TABLE service_record (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    order_id       BIGINT       NOT NULL,
    staff_id       BIGINT       NOT NULL,
    depart_time    DATETIME     DEFAULT NULL,
    arrive_time    DATETIME     DEFAULT NULL,
    before_photos  VARCHAR(500) DEFAULT NULL,
    after_photos   VARCHAR(500) DEFAULT NULL,
    pet_condition  VARCHAR(255) DEFAULT NULL,
    report         TEXT,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务记录';

CREATE TABLE review (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    staff_id    BIGINT       NOT NULL,
    rating      TINYINT      NOT NULL,
    tags        VARCHAR(128) DEFAULT NULL,
    content     VARCHAR(500) DEFAULT NULL,
    reply       VARCHAR(500) DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order (order_id),
    KEY idx_staff (staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价';

CREATE TABLE complaint (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    images      VARCHAR(500) DEFAULT NULL,
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0待受理 1处理中 2已解决 3已关闭',
    result      VARCHAR(500) DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉工单';

CREATE TABLE settlement (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     BIGINT       NOT NULL,
    staff_id     BIGINT       NOT NULL,
    order_amount DECIMAL(10,2) NOT NULL,
    commission   DECIMAL(10,2) NOT NULL,
    staff_income DECIMAL(10,2) NOT NULL,
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0待结算 1可提现 2冻结',
    settle_time  DATETIME     DEFAULT NULL,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order (order_id),
    KEY idx_staff (staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算单';

CREATE TABLE notification (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    title       VARCHAR(100) NOT NULL,
    content     VARCHAR(500) NOT NULL,
    biz_id      BIGINT       DEFAULT NULL,
    is_read     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知';

CREATE TABLE knowledge_chunk (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    title       VARCHAR(128) NOT NULL,
    category    VARCHAR(32)  NOT NULL,
    source      VARCHAR(128) NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    keywords    VARCHAR(255) DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='养护知识库';

CREATE TABLE ai_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    session_id  VARCHAR(64)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    content     TEXT         NOT NULL,
    citations   VARCHAR(2000) DEFAULT NULL,
    tool_calls  VARCHAR(1000) DEFAULT NULL,
    refused     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话消息';

-- =============================================================
-- 初始数据
-- =============================================================
INSERT INTO app_user (phone, nickname, role, staff_id) VALUES
('13800000000', '平台管理员', 2, NULL),
('13900000001', '张美美', 1, 1),
('13900000002', '李强', 1, 2),
('13900000003', '王芳', 1, 3);

INSERT INTO staff (user_id, name, phone, level, rating, order_count, size_types, service_area, lat, lng) VALUES
(2, '张美美', '13900000001', 3, 4.95, 128, 'S,M,L', '西湖区', 30.259000, 120.130000),
(3, '李强', '13900000002', 2, 4.80, 86, 'M,L', '西湖区', 30.270000, 120.150000),
(4, '王芳', '13900000003', 1, 4.70, 42, 'S,M', '拱墅区', 30.310000, 120.160000);

INSERT INTO staff_schedule (staff_id, weekday, start_time, end_time) VALUES
(1,1,'09:00:00','18:00:00'),(1,2,'09:00:00','18:00:00'),(1,3,'09:00:00','18:00:00'),
(1,4,'09:00:00','18:00:00'),(1,5,'09:00:00','18:00:00'),(1,6,'10:00:00','16:00:00'),
(2,1,'10:00:00','20:00:00'),(2,2,'10:00:00','20:00:00'),(2,3,'10:00:00','20:00:00'),
(2,4,'10:00:00','20:00:00'),(2,5,'10:00:00','20:00:00'),
(3,2,'09:00:00','17:00:00'),(3,4,'09:00:00','17:00:00'),(3,6,'09:00:00','17:00:00'),(3,7,'09:00:00','17:00:00');

INSERT INTO service_item (name, category, description, duration_min, price_s, price_m, price_l, status, sort) VALUES
('上门基础洗护', '洗护', '洗澡、吹干、梳毛、基础清洁', 60, 99.00, 129.00, 169.00, 1, 1),
('上门精细美容', '美容', '洗护 + 修剪造型 + 指甲 + 洁耳', 60, 159.00, 199.00, 259.00, 1, 2),
('上门喂养', '喂养', '喂食、换水、铲屎、陪玩 30 分钟', 60, 49.00, 59.00, 69.00, 1, 3),
('上门遛狗', '遛狗', '户外遛狗 45 分钟，含拾便', 60, 59.00, 69.00, 79.00, 1, 4),
('家庭寄养（每天）', '寄养', '家庭环境寄养，含喂养与遛弯', 60, 128.00, 158.00, 198.00, 1, 5),
('疫苗陪同就诊', '陪诊', '陪同前往宠物医院，协助沟通', 60, 139.00, 159.00, 179.00, 1, 6);

INSERT INTO knowledge_chunk (title, category, source, content, keywords) VALUES
('幼猫喂养频率', '饮食', '宠物营养基础手册', '3 个月以内的幼猫建议每天喂 4 到 5 次，少量多餐；3 到 6 个月每天 3 到 4 次；6 个月以后逐步过渡到每天 2 到 3 次。幼猫消化能力弱，突然换粮容易软便。', '幼猫,喂养,频率,少食多餐'),
('幼犬喂养频率', '饮食', '宠物营养基础手册', '幼犬建议每天喂 3 到 4 次，定时定量；3 个月后可以逐步减少到 3 次。大型犬幼犬生长快，需要控制钙磷比例，避免过度补钙。', '幼犬,喂养,频率,补钙'),
('换粮过渡方法', '饮食', '宠物营养基础手册', '换粮采用 7 天过渡法：第 1 到 2 天新粮占 25%，第 3 到 4 天占 50%，第 5 到 6 天占 75%，第 7 天全部换成新粮。期间观察排便和精神状态。', '换粮,过渡,软便,7天'),
('成猫每日食量', '饮食', '宠物营养基础手册', '成猫每日干粮量约为体重的 2% 到 3%，例如 4 公斤的猫每天约 50 到 70 克，具体以粮袋建议和体况评分为准。零食不超过每日总热量的 10%。', '成猫,食量,克数,体重'),
('狗狗不能吃的食物', '饮食', '宠物安全喂养指南', '巧克力、葡萄与葡萄干、洋葱与大蒜、夏威夷果、木糖醇、酒精、生面团对狗有毒，误食可能出现呕吐、抽搐、溶血等症状，应立即就医。', '狗,禁忌,巧克力,葡萄,洋葱,木糖醇'),
('猫咪不能吃的食物', '饮食', '宠物安全喂养指南', '猫不能吃洋葱、大蒜、韭菜、巧克力、葡萄、酒精和含咖啡因饮料；牛奶多数成年猫乳糖不耐受，建议选择宠物专用奶。', '猫,禁忌,洋葱,牛奶,乳糖'),
('猫咪饮水不足', '饮食', '宠物护理指南', '猫天生饮水少，建议使用流动饮水机、多点放置水碗，或增加湿粮比例。每日饮水量约为每公斤体重 40 到 60 毫升。', '猫,饮水,湿粮,泌尿'),
('老年犬饮食调整', '饮食', '宠物营养基础手册', '7 岁以上犬只建议选择低脂、易消化、含关节支持成分（如葡萄糖胺）的粮；适当增加优质蛋白，控制体重，定期体检。', '老年犬,饮食,关节,体重'),
('疫苗周期', '疫苗', '宠物医疗常识', '幼犬幼猫通常在 6 到 8 周龄开始首针，间隔 3 到 4 周接种，共 3 针；成年后每年加强一次。狂犬疫苗按当地规定每年接种。', '疫苗,周期,幼犬,幼猫,狂犬'),
('疫苗接种后反应', '疫苗', '宠物医疗常识', '接种后 1 到 2 天可能出现精神略差、食欲下降、注射部位轻微肿胀，通常自行缓解；若出现面部肿胀、呼吸急促、持续呕吐，应立即就医。', '疫苗,反应,过敏,就医'),
('体外驱虫', '疫苗', '宠物医疗常识', '体外驱虫建议每月一次，常用成分包括非泼罗尼、塞拉菌素等；体内驱虫幼年每月一次，成年每 3 个月一次，具体遵医嘱。', '驱虫,体外,体内,频率'),
('猫咪洗澡频率', '护理', '宠物护理指南', '健康成年猫一般 1 到 3 个月洗一次即可，室内猫可更长；频繁洗澡会破坏皮肤屏障。洗澡后必须彻底吹干，避免真菌感染。', '猫,洗澡,频率,吹干'),
('狗狗洗澡频率', '护理', '宠物护理指南', '狗狗建议 2 到 4 周洗一次，视活动量和皮肤状况调整；使用宠物专用香波，人类洗发水会破坏皮肤酸碱平衡。', '狗,洗澡,频率,香波'),
('剪指甲注意事项', '护理', '宠物护理指南', '剪指甲只剪透明部分，避开粉色血线；深色指甲可分次少量修剪。每 2 到 4 周修剪一次，奔跑少的宠物需要更频繁。', '指甲,修剪,血线'),
('耳朵清洁', '护理', '宠物护理指南', '每周检查一次耳道，用宠物洗耳液清洁外耳，不要用棉签深入耳道。出现异味、大量分泌物、频繁甩头应就医。', '耳朵,清洁,洗耳液,异味'),
('换毛季梳毛', '护理', '宠物护理指南', '春秋换毛季建议每天梳毛 1 次，帮助去除浮毛、减少毛球；长毛猫可配合化毛膏或猫草。', '换毛,梳毛,毛球,化毛膏'),
('分离焦虑表现', '行为', '宠物行为指南', '分离焦虑常见表现：主人离开后持续吠叫、破坏门家具、随地排泄、拒食。可通过渐进式离开训练、益智玩具、增加运动量缓解，严重时咨询行为医生。', '分离焦虑,吠叫,破坏,训练'),
('幼犬定点排便', '行为', '宠物行为指南', '幼犬在进食后 15 到 30 分钟、睡醒后容易排便，抓住时机带到固定地点，成功后立即奖励；不要事后惩罚，否则会让狗害怕排便。', '幼犬,排便,训练,奖励'),
('猫咪应激', '行为', '宠物行为指南', '搬家、洗澡、就医、陌生人来访都可能引发应激，表现为躲藏、拒食、乱尿。可提前使用费洛蒙产品，保持环境稳定，减少强行互动。', '猫,应激,躲藏,费洛蒙'),
('上门服务安全须知', '安全', '平台服务规范', '服务者上门需核对订单与宠物信息，服务前拍照记录宠物状态；发现宠物有攻击性、怀孕、明显生病时，应暂停服务并联系平台，不得强行操作。', '上门,安全,攻击性,怀孕,暂停'),
('宠物受伤处理', '安全', '平台服务规范', '服务过程中如宠物受伤，应立即停止服务、安抚宠物、联系主人并视情况送医；同时上报平台，保留照片和就诊记录，由平台协调赔付。', '受伤,送医,上报,赔付'),
('服务取消规则', '规则', '平台交易规则', '服务开始前 24 小时以上取消全额退款；2 到 24 小时取消扣除 20%；2 小时内取消扣除 50%；服务开始后不支持取消，可提交售后工单。', '取消,退款,违约金,24小时'),
('服务改约规则', '规则', '平台交易规则', '每单最多改约 1 次，需在服务开始前 4 小时以上操作；改约需重新选择可用时间，可能产生时段差价。', '改约,时间,4小时'),
('服务范围与上门费', '规则', '平台交易规则', '平台当前覆盖杭州西湖区、拱墅区；门店 5 公里内免上门费，超出部分按每公里 5 元收取，具体以下单试算为准。', '范围,上门费,公里,杭州');

-- 演示用户与宠物（手机号 13700000001，登录验证码由 /api/auth/sms 随机生成）
INSERT INTO app_user (phone, nickname, role) VALUES ('13700000001', '演示用户', 0);
INSERT INTO pet (user_id, name, species, breed, gender, birthday, weight_kg, sterilized, allergies, traits, notes) VALUES
(5, '豆豆', 'CAT', '英短', 1, '2024-03-01', 4.50, 1, '无', '亲人,怕吹风机', '不喜欢被抱太久'),
(5, '旺财', 'DOG', '柯基', 1, '2022-06-15', 12.00, 1, '鸡肉过敏', '活泼,亲人', '出门会爆冲');
INSERT INTO address (user_id, contact, phone, city, district, detail, lat, lng, is_default) VALUES
(5, '演示用户', '13700000001', '杭州市', '西湖区', '文三路 100 号 1 幢 301', 30.276000, 120.135000, 1);
