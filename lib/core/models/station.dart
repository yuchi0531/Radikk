import 'package:equatable/equatable.dart';

class Station extends Equatable {
  final String id;
  final String name;
  final String? logoUrl;
  final String? bannerUrl;
  final String? detailUrl;
  final String? playlistCreateUrl;
  final List<String> areaIds;

  const Station({
    required this.id,
    required this.name,
    this.logoUrl,
    this.bannerUrl,
    this.detailUrl,
    this.playlistCreateUrl,
    this.areaIds = const [],
  });

  Station copyWith({
    String? id,
    String? name,
    String? logoUrl,
    String? bannerUrl,
    String? detailUrl,
    String? playlistCreateUrl,
    List<String>? areaIds,
  }) {
    return Station(
      id: id ?? this.id,
      name: name ?? this.name,
      logoUrl: logoUrl ?? this.logoUrl,
      bannerUrl: bannerUrl ?? this.bannerUrl,
      detailUrl: detailUrl ?? this.detailUrl,
      playlistCreateUrl: playlistCreateUrl ?? this.playlistCreateUrl,
      areaIds: areaIds ?? this.areaIds,
    );
  }

  @override
  List<Object?> get props =>
      [id, name, logoUrl, bannerUrl, playlistCreateUrl, areaIds];
}
