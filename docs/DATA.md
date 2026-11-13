# 데이터 출처

`app/src/main/assets/routes.json`은 아래 자료로 계산한 결과입니다.

| 자료 | 쓰임 | 출처 |
|---|---|---|
| 국가표준노드링크 | 도로 선형, 제한속도, 후보 경로 계산 | 국가교통정보센터 국가표준노드링크 [2026-10-14], https://www.its.go.kr/nodelink/nodelinkRef (NODELINKDATA) |
| 휴게소·졸음쉼터 위치 | 쉬어 갈 곳 후보 | OpenStreetMap 기여자(2026-10-22 추출, ODbL). © OpenStreetMap contributors |
| 가족사랑화장실, 졸음쉼터 현황 | 휴게소 부가 정보 | 공공데이터포털 한국도로공사_장애인 및 가족사랑 화장실 운영여부 및 위치(2026-03-09), https://www.data.go.kr/data/15142833/fileData.do / 한국도로공사_졸음쉼터 설치현황(2026-08-01), https://www.data.go.kr/data/15043710/fileData.do |

경로 5개는 서울 상일IC 부근에서 속초 시내와 강릉 시내로 가는 길입니다. 좌표와 거리는 노드링크 선형을 100m 간격으로 다시 찍은 값이라 실제 내비 경로와 다를 수 있습니다.

## 이용 조건

- 휴게소·졸음쉼터 이름과 위치는 OpenStreetMap 자료를 가공해 넣었습니다. © OpenStreetMap contributors, Open Database License(ODbL 1.0, https://opendatacommons.org/licenses/odbl/). 이 부분을 다시 쓰거나 배포할 때도 ODbL을 따릅니다.
- 국가표준노드링크와 한국도로공사 자료는 각 제공처(국가교통정보센터, 공공데이터포털)의 이용 조건을 따르며, 출처를 위와 같이 밝힙니다. 이 저장소에는 원자료를 넣지 않았고, 계산한 경로 선형(100m 간격 좌표)과 결과 수치만 넣었습니다.
- `routes.json` 안에서 ODbL을 따르는 부분은 `stops`(휴게소·졸음쉼터 이름과 위치)입니다.

멀미 누적 수치는 모형값이며 의학적 진단이 아닙니다. 아이 기준선(25)은 예시 값이고, 앱은 "속 안 좋아" 기록으로 다음 여행의 기준선을 낮춥니다.
