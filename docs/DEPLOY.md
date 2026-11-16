# 배포 가이드 (EC2 + Docker Compose)

EC2 한 대에 Nginx · Spring Boot · PostgreSQL · Redis를 `docker-compose.prod.yml`로 올린다. 프론트엔드는 Vercel이다.

| 용도 | 주소 |
|---|---|
| 프론트엔드 | `https://libsync.cloud` (Vercel) |
| 백엔드 API | `https://api.libsync.cloud` (이 서버) |

외부에 열리는 포트는 nginx의 80/443뿐이다. 백엔드·Postgres·Redis는 컨테이너 내부 네트워크로만 접근한다.

## 0. 사전 조건
- EC2(Ubuntu 24.04, 메모리 2GB 이상), Elastic IP 연결
- 보안그룹: 80, 443은 전체 허용 / 22는 내 IP만 / 5432, 6379, 8080은 열지 않음
- DNS: `api.libsync.cloud` A 레코드가 Elastic IP를 가리킴 (`dig api.libsync.cloud`로 확인)

## 1. 서버 준비 (최초 1회)

```bash
# Docker 설치
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER   # 적용하려면 다시 로그인

# 스왑 2GB (메모리 부족으로 컨테이너가 죽는 것을 막는다)
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 2. 코드와 환경변수

```bash
git clone https://github.com/404-FOUND-INHA/Backend.git smart-library
cd smart-library
cp .env.prod.example .env
nano .env
```

`.env`에서 `CHANGE_ME`를 모두 새 값으로 바꾼다. `.env`는 절대 커밋하지 않는다.

```bash
openssl rand -base64 24   # DB_PASSWORD, REDIS_PASSWORD
openssl rand -base64 48   # JWT_SECRET (32바이트 이상)
```

> `spring.profiles.active=dev`를 `.env`에 넣지 않는다. 넣으면 시드 계정(`Password1234!`)이 운영 DB에 들어간다.

## 3. 첫 실행 (HTTP)

`.env`의 `NGINX_CONF=http` 상태로 시작한다.

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps          # 모두 Up (healthy)인지 확인
docker compose -f docker-compose.prod.yml logs backend | tail -30   # Flyway 적용, Started 확인
curl http://api.libsync.cloud/actuator/health          # {"status":"UP",...}
```

처음 빌드는 몇 분 걸린다.

## 4. HTTPS 적용

Vercel(HTTPS)에서 호출하려면 백엔드도 HTTPS여야 한다. 80 포트가 열려 있고 DNS가 반영된 상태에서 진행한다.

```bash
# 1) 인증서 발급 (Let's Encrypt)
docker compose -f docker-compose.prod.yml run --rm certbot certonly \
  --webroot -w /var/www/certbot -d api.libsync.cloud \
  --email <관리용 이메일> --agree-tos --no-eff-email

# 2) nginx를 HTTPS 설정으로 전환: .env 의 NGINX_CONF=http 를 https 로 바꾼 뒤
docker compose -f docker-compose.prod.yml up -d nginx

# 3) 확인
curl https://api.libsync.cloud/actuator/health
```

### 인증서 자동 갱신
인증서는 90일마다 만료된다. 매주 갱신을 시도하도록 cron을 등록한다 (`crontab -e`).

```cron
0 4 * * 1 cd /home/ubuntu/smart-library && docker compose -f docker-compose.prod.yml run --rm certbot renew --quiet && docker compose -f docker-compose.prod.yml exec -T nginx nginx -s reload
```

잘 되는지는 `docker compose -f docker-compose.prod.yml run --rm certbot renew --dry-run`으로 확인한다.

## 5. 초기 사서 계정
회원가입으로 만들어지는 계정은 항상 `STUDENT_WORKER`다. 가입한 뒤 DB에서 권한을 올린다.

```bash
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U capstone -d smart_library \
  -c "UPDATE app_user SET role = 'LIBRARIAN' WHERE email = '사서이메일@example.com';"
```

## 6. 코드 업데이트 배포

```bash
cd smart-library
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

DB 스키마가 바뀌면(`db/migration`에 새 파일) 백엔드가 시작될 때 Flyway가 자동 적용한다.

## 7. 자주 쓰는 명령

```bash
docker compose -f docker-compose.prod.yml ps                    # 상태
docker compose -f docker-compose.prod.yml logs -f backend       # 백엔드 로그
docker compose -f docker-compose.prod.yml restart backend       # 백엔드 재시작
docker stats --no-stream                                        # 메모리 사용량
```

## 8. DB 백업 (실사용 전에 꼭)
DB가 서버 한 대에 있어서, 인스턴스가 사라지면 데이터도 같이 사라진다.

```bash
# 백업 (pg_dump 가 실패하면 파일을 남기지 않는다)
set -o pipefail
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U capstone smart_library | gzip > backup.tmp \
  && mv backup.tmp backup_$(date +%F).sql.gz \
  || { rm -f backup.tmp; echo "백업 실패" >&2; }

# 복구 (빈 DB에)
gunzip -c backup_2026-01-01.sql.gz | docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U capstone -d smart_library
```

cron으로 매일 실행하고 S3 등 서버 밖에 보관한다. 복구가 되는지 한 번은 직접 확인한다.

## 문제 해결
| 증상 | 확인 |
|---|---|
| `up` 시 `.env 에 ... 를 설정하세요` | `.env`에 해당 값이 비어 있다 |
| 백엔드가 `JWT_SECRET 은 32바이트 이상` 오류로 종료 | `JWT_SECRET`이 `CHANGE_ME`이거나 너무 짧다 |
| 브라우저에서 CORS 오류 | `.env`의 `CORS_ALLOWED_ORIGINS`에 프론트 주소가 정확히 있는지(끝에 `/` 없이), 바꿨다면 `up -d`로 재시작 |
| 브라우저에서 mixed content 오류 | 4단계 HTTPS가 적용되지 않았다 |
| nginx가 안 뜸 | `NGINX_CONF=https`인데 인증서가 아직 없다. `http`로 되돌린다 |
| 컨테이너가 계속 죽음 | `docker stats`로 메모리 확인, 스왑이 켜져 있는지 확인 |
