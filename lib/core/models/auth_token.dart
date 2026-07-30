class AuthToken {
  final String token;
  final int keyOffset;
  final int keyLength;
  final String? areaId;
  final String? areaName;
  final DateTime createdAt;

  const AuthToken({
    required this.token,
    required this.keyOffset,
    required this.keyLength,
    this.areaId,
    this.areaName,
    required this.createdAt,
  });

  /// 期限切れかどうか（70分）
  bool get isExpired {
    return DateTime.now().difference(createdAt).inSeconds > 4200;
  }

  Map<String, dynamic> toJson() => {
        'token': token,
        'keyOffset': keyOffset,
        'keyLength': keyLength,
        'areaId': areaId,
        'areaName': areaName,
        'createdAt': createdAt.toIso8601String(),
      };

  factory AuthToken.fromJson(Map<String, dynamic> json) => AuthToken(
        token: json['token'] as String,
        keyOffset: json['keyOffset'] as int,
        keyLength: json['keyLength'] as int,
        areaId: json['areaId'] as String?,
        areaName: json['areaName'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}
