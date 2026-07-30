import 'package:equatable/equatable.dart';

class Program extends Equatable {
  final String id;
  final String title;
  final String? description;
  final String? personality;
  final String? imageUrl;
  final String stationId;
  final String stationName;
  final DateTime startTime;
  final DateTime endTime;
  final Duration duration;
  final String? infoUrl;
  final String? shareUrl;

  const Program({
    required this.id,
    required this.title,
    this.description,
    this.personality,
    this.imageUrl,
    required this.stationId,
    required this.stationName,
    required this.startTime,
    required this.endTime,
    required this.duration,
    this.infoUrl,
    this.shareUrl,
  });

  factory Program.fromJson(Map<String, dynamic> json, String stationId) {
    final startTime = DateTime.tryParse(json['ft'] ?? '') ?? DateTime.now();
    final endTime = DateTime.tryParse(json['to'] ?? '') ?? DateTime.now();
    return Program(
      id: json['id']?.toString() ?? '',
      title: json['title'] ?? '',
      description: json['desc'],
      personality: json['pfm'],
      imageUrl: json['img'],
      stationId: stationId,
      stationName: json['stationName'] ?? '',
      startTime: startTime,
      endTime: endTime,
      duration: endTime.difference(startTime),
      infoUrl: json['info'],
      shareUrl: json['share'],
    );
  }

  /// 放送中かどうか
  bool get isOnAir {
    final now = DateTime.now();
    return now.isAfter(startTime) && now.isBefore(endTime);
  }

  /// タイムフリーで聴けるかどうか（放送終了後7日以内）
  bool get isTimefreeAvailable {
    final now = DateTime.now();
    return now.isAfter(endTime) &&
        now.isBefore(endTime.add(const Duration(days: 7)));
  }

  @override
  List<Object?> get props => [
        id,
        title,
        stationId,
        startTime,
        endTime,
      ];
}
