class AreaInfo {
  final String id;
  final String japanese;
  final String roman;
  const AreaInfo({required this.id, required this.japanese, required this.roman});
}

class AreaMap {
  static const List<AreaInfo> areas = [
    AreaInfo(id: 'JP1', japanese: '北海道', roman: 'HOKKAIDO'),
    AreaInfo(id: 'JP2', japanese: '青森', roman: 'AOMORI'),
    AreaInfo(id: 'JP3', japanese: '岩手', roman: 'IWATE'),
    AreaInfo(id: 'JP4', japanese: '宮城', roman: 'MIYAGI'),
    AreaInfo(id: 'JP5', japanese: '秋田', roman: 'AKITA'),
    AreaInfo(id: 'JP6', japanese: '山形', roman: 'YAMAGATA'),
    AreaInfo(id: 'JP7', japanese: '福島', roman: 'FUKUSHIMA'),
    AreaInfo(id: 'JP8', japanese: '茨城', roman: 'IBARAKI'),
    AreaInfo(id: 'JP9', japanese: '栃木', roman: 'TOCHIGI'),
    AreaInfo(id: 'JP10', japanese: '群馬', roman: 'GUNMA'),
    AreaInfo(id: 'JP11', japanese: '埼玉', roman: 'SAITAMA'),
    AreaInfo(id: 'JP12', japanese: '千葉', roman: 'CHIBA'),
    AreaInfo(id: 'JP13', japanese: '東京', roman: 'TOKYO'),
    AreaInfo(id: 'JP14', japanese: '神奈川', roman: 'KANAGAWA'),
    AreaInfo(id: 'JP15', japanese: '新潟', roman: 'NIIGATA'),
    AreaInfo(id: 'JP16', japanese: '富山', roman: 'TOYAMA'),
    AreaInfo(id: 'JP17', japanese: '石川', roman: 'ISHIKAWA'),
    AreaInfo(id: 'JP18', japanese: '福井', roman: 'FUKUI'),
    AreaInfo(id: 'JP19', japanese: '山梨', roman: 'YAMANASHI'),
    AreaInfo(id: 'JP20', japanese: '長野', roman: 'NAGANO'),
    AreaInfo(id: 'JP21', japanese: '岐阜', roman: 'GIFU'),
    AreaInfo(id: 'JP22', japanese: '静岡', roman: 'SHIZUOKA'),
    AreaInfo(id: 'JP23', japanese: '愛知', roman: 'AICHI'),
    AreaInfo(id: 'JP24', japanese: '三重', roman: 'MIE'),
    AreaInfo(id: 'JP25', japanese: '滋賀', roman: 'SHIGA'),
    AreaInfo(id: 'JP26', japanese: '京都', roman: 'KYOTO'),
    AreaInfo(id: 'JP27', japanese: '大阪', roman: 'OSAKA'),
    AreaInfo(id: 'JP28', japanese: '兵庫', roman: 'HYOGO'),
    AreaInfo(id: 'JP29', japanese: '奈良', roman: 'NARA'),
    AreaInfo(id: 'JP30', japanese: '和歌山', roman: 'WAKAYAMA'),
    AreaInfo(id: 'JP31', japanese: '鳥取', roman: 'TOTTORI'),
    AreaInfo(id: 'JP32', japanese: '島根', roman: 'SHIMANE'),
    AreaInfo(id: 'JP33', japanese: '岡山', roman: 'OKAYAMA'),
    AreaInfo(id: 'JP34', japanese: '広島', roman: 'HIROSHIMA'),
    AreaInfo(id: 'JP35', japanese: '山口', roman: 'YAMAGUCHI'),
    AreaInfo(id: 'JP36', japanese: '徳島', roman: 'TOKUSHIMA'),
    AreaInfo(id: 'JP37', japanese: '香川', roman: 'KAGAWA'),
    AreaInfo(id: 'JP38', japanese: '愛媛', roman: 'EHIME'),
    AreaInfo(id: 'JP39', japanese: '高知', roman: 'KOCHI'),
    AreaInfo(id: 'JP40', japanese: '福岡', roman: 'FUKUOKA'),
    AreaInfo(id: 'JP41', japanese: '佐賀', roman: 'SAGA'),
    AreaInfo(id: 'JP42', japanese: '長崎', roman: 'NAGASAKI'),
    AreaInfo(id: 'JP43', japanese: '熊本', roman: 'KUMAMOTO'),
    AreaInfo(id: 'JP44', japanese: '大分', roman: 'OITA'),
    AreaInfo(id: 'JP45', japanese: '宮崎', roman: 'MIYAZAKI'),
    AreaInfo(id: 'JP46', japanese: '鹿児島', roman: 'KAGOSHIMA'),
    AreaInfo(id: 'JP47', japanese: '沖縄', roman: 'OKINAWA'),
  ];
}
