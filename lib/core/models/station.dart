import 'package:equatable/equatable.dart';

class Station extends Equatable {
  final String id;
  final String name;
  final String? logoUrl;
  final String? bannerUrl;
  final String? detailUrl;
  final List<String> areaIds;

  const Station({
    required this.id,
    required this.name,
    this.logoUrl,
    this.bannerUrl,
    this.detailUrl,
    this.areaIds = const [],
  });

  @override
  List<Object?> get props => [id, name, logoUrl, bannerUrl, areaIds];
}
