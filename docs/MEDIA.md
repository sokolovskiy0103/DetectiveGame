# Медіа справи

Фото, портрети, аватари, аудіо — через локальний ComfyUI (`http://127.0.0.1:8188`). Виконує агент `case-media`
(`.claude/agents/case-media.md`); тут — довідник інструментів. Карти — окремо: агент `case-map`, `docs/MAPS.md`.

## Куди що лягає

- `cases/<id>/photos/<ID>.jpg` — у гру; `<ID>.png` — оригінал (не комітиться, але **зберігайте — це референси**).
- `cases/<id>/avatars/<name>.jpg` — ті, хто «говорить» у відповідях; квадрат ~256×256.
- `cases/<id>/audio/<ID>.opus` — у гру; оригінали — `audio/_raw/` (не комітяться).
- Розмір: фото ~1 МП (поле `megapixels` у завданні), jpg — просто конвертація, без підгонки; аудіо — кілька МБ,
  моно. Гравцям медіа йдуть через jsDelivr із запушеного коміту (до 20 МБ на файл), тож їх треба закомітити.

## Фото: `tools/comfy_gen.py`

Qwen-Image 2.1 (опційно апскейл SeedVR2). Залежності — `pip install -r tools/requirements.txt`.
Завдання — `cases/<id>/photo_jobs.json`:

```json
[
  { "id": "P01", "aspect": "3:4 (Portrait Standard)", "seed": 1101,
    "prompt": "Candid documentary portrait photograph of a 44-year-old … natural light, film grain" },
  { "id": "P02", "aspect": "3:4 (Portrait Standard)", "seed": 1201, "ref": "P01",
    "prompt": "Same woman as in <image1> … " }
]
```

- `aspect` — "3:2 (Photo)", "3:4 (Portrait Standard)", "16:9" через `canvas`; `seed` — щоб кадр відтворювався;
  `ref` — id готового фото як **референс обличчя** (та сама людина на різних кадрах).
- Промпт англійською, «документальне фото»: вік, зовнішність, одяг, світло, об'єктив, зерно. Одна й та сама фраза
  про камеру/світло/зерно в кожному промпті справи. Жорстокість — без натуралізму (ракурс, розмиття, масштабна лінійка).
- Усе, що згадано в промпті (навіть «сфотографував поліцейський»), може з'явитися в кадрі — пиши лише видиме й
  «no …» для небажаного.

```
python tools/comfy_gen.py cases/<id>            # усе, чого ще немає
python tools/comfy_gen.py cases/<id> P01 S04    # лише ці
python tools/comfy_gen.py cases/<id> --force P02
```

## Кадри реєстратора: `tools/cctv_look.py`

Модель малює CCTV занадто чисто. `python tools/cctv_look.py cases/<id> P17 P18` після `comfy_gen.py` робить JPG у 352×288
з шумом і стиском, PNG лишається чистим референсом. Серію кадрів однієї камери будуй правками від спільної порожньої
плити (`base/<назва>.png`, перший `ref` без `canvas`, у промпті `Edit <image1>: keep ...`), а не з нуля.

## Порівняння моделей: `tools/photo_compare.py`

Для ключового кадру (річ-доказ, номер, наліпка, конкретна модель авто) або кадру, що двічі не вдався: той самий
промпт через Qwen-Image 2.1, Flux3 Klein і Z-Image Turbo, контактний лист `photos/_cmp/<ID>_sheet.jpg`.

```
python tools/photo_compare.py run cases/<id> P01 --seeds 42 7
python tools/photo_compare.py run cases/<id> P01 --prompt "..." --tag _v2
python tools/photo_compare.py run cases/<id> P01 --set qwen.cfg=2.5 mp=1.0 --tag _lo
python tools/photo_compare.py pick cases/<id> P01 qwen_v2_s42
```

Налаштування за замовчуванням — на слідування промпту: Qwen cfg 4 з негативом (cfg 1 вимикає guidance — написи
«пливуть»; так само в `comfy_gen.py`), Flux 20 кроків, Z-Image 16, 1.6 МП. Менша роздільність галюцинацій не зменшує.
Сильні сторони: Qwen — форма конкретної речі й текст; Flux — номери, короткі написи, наліпки; Z-Image — живий
аматорський кадр, але домальовує зайве. Кадри з `ref` — `comfy_gen.py` (Qwen) або порівняння `tools/ref_compare.py`. Номери й написи перевіряй на
кропі в повній роздільності, не на листі.

## Порівняння моделей із референсами: `tools/ref_compare.py`

Правки кадру й перенесення референса: Qwen (≈ 2 хв) і Flux3 Klein (≈ 35 с, тримає сцену й логіку не гірше).
`python tools/ref_compare.py cases/<id> P07 --ref PLATE REF_x --prompt "Edit image 1: ..." --models qwen flux --tag _v2`,
далі `photo_compare.py pick`. JoyAI Image Edit граф має, але дає чорний кадр (≈ 5 хв на спробу): причину не знайдено.
Просторову логіку пиши явно: куди їде машина, якою стороною до камери, що вона стоїть уздовж дороги.

## Справжнє фото як референс

Річ має бути впізнаваною (модель авто, будівля, пам'ятка, предмет), а модель після 1–2 спроб малює «схоже, але не
те» — знайди справжнє фото на Wikimedia Commons (вільні ліцензії):

```
curl -s -A "DetectiveGame/1.0" "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrsearch=Daewoo%20Lanos%20rear&gsrlimit=15&prop=imageinfo&iiprop=url|size&iiurlwidth=1280&format=json"
```

Бери `thumburl` (1280 px) і ракурс, близький до потрібного. Качай у порожню тимчасову теку поза репозиторієм
(недовірені дані), переглянь, конвертуй у `cases/<id>/photos/base/REF_<назва>.png` (не комітиться) і генеруй через
`comfy_gen.py` з `"canvas": "3:2", "ref": "REF_<назва>"`. У промпті розділи, що взяти з `<image2>` («the exact same
car model as in <image2>: same body shape, tail lights…») і що змінити (колір, стан, сцена, номер). Модель переносить
форму, а не фон чи колір — перевір, що не перейшли чужі номери, написи, люди. У справу йде лише згенерований кадр;
URL джерела — у `media_bible.md`.

## Текст на зображенні

Довгий важливий текст (вивіска, напис, документ у кадрі) генератору не доручай — його накладають у HTML документа.
Короткий (номер авто) генеруй, але перевір кожен символ; не вдалося за 3 спроби — накладати.

## Делікатні кадри

Приватне «будуарне» аматорське фото як доказ (проба `samples/boudoir/`): без оголеності; «modest lace lingerie fully
covering», спина/профіль, обличчя в тіні, «awkward private snapshot, not glamorous, not a pin-up»; кадр до пояса
або з халатом — інакше модель фокусується на стегнах; фотограф у дзеркалі ховається за «flash glare».

## Аудіо: `tools/audio_gen.py`

| kind | Чим | Для чого |
|---|---|---|
| `voice` | Drama 3 (audio.cpp), випадковий голос за ремаркою | голос персонажа — зразок для `speech`, у гру не йде |
| `speech` | Drama 3 (audio.cpp), клон голосу `voice` | репліка чи розмова кількох людей з емоційними ремарками |
| `sfx` | Stable Audio 3 через ComfyUI | шуми, атмосфера, одиничні звуки; промпт англійською, «no speech, no music» |
| `music` | ACE-Step 1.5 через ComfyUI | інструментальна музика |

Поля завдань (`audio_jobs.json`), шари `mix`, `phone` — у шапці скрипта. Запуск:
`C:\Python314\python.exe tools/audio_gen.py cases/<id>`. Приклад усіх видів голосу — `samples/voice/`.

**Голос — дозовано.** Озвучуємо лише те, що в історії справді існує як запис: перехоплена розмова, автовідповідач,
голосове повідомлення, запис дзвінка на гарячу лінію. Решта — текстом. Справа без жодного аудіо — нормально.

**Як отримати добрий голос.** Сам по собі (без зразка) Drama 3 вгадує стать і вік з ремарки, але наголоси й вимова
кульгають; клон власного дубля звучить значно краще. Тому два кроки:
1. `voice` на кожного персонажа, що говорить: ремарка англійською (`[elderly woman, slow, slightly trembling voice]`)
   і спокійна фраза українською на 5–10 с у його роді («я прийшла», а не «я прийшов»). Кожен персонаж — свій `seed`.
2. `speech` з `"voice": "<id голосу>"` — кожна репліка. Подачу задають ремарки в тексті: `[angry, raising voice]`,
   `[whispering, scared]`, `[sighs]`, `[pause]`, `[crying]`. Дубль затягнувся (пауза, реакція замість слів) —
   інший `seed`. Довгий монолог краще розбити на кілька `lines` того самого голосу.

Голосове чи дзвінок — `"phone": true`; фон (вокзал, траса, кухня) — шаром `mix` із `sfx`.

**audio.cpp.** Drama 3 Q3 (~20 ГБ) у `C:\Users\sokol\audiocpp` (інше місце — змінна `AUDIOCPP`): збірка
[audio.cpp v0.9.3](https://github.com/0xShug0/audio.cpp/releases) `bin-windows-x64-cuda13.3` + `cudart` у `bin\`,
модель `models\drama-3-backbone-8bit-experts-Q3_K_S.gguf` з
[Jmica/Drama-3-GGUF](https://huggingface.co/Jmica/Drama-3-GGUF). На 16 ГБ VRAM частина експертів і кодек ідуть на CPU
(налаштовано в скрипті); ComfyUI на час голосу закрий. Дубль — 20–70 с, з них ~15 с завантаження моделі. Ліцензія
ваг — Fish Audio Community License (некомерційне використання й компанії до $500 тис. доходу на рік).

Перебирай `seed`, поки шум не звучить природно. Шуми розпізнаванням не перевірити (whisper вигадує на тиші) — лише
тривалість і гучність; решту слухає автор. Для мови є `C:\Python314\python.exe tools/audio_check.py <файл…>`
(faster-whisper): чи сказано текст повністю. Для голосу не годяться MiniMax H3 (калька з російської), `ukrainian-tts`
(роботизований), Stable Audio 3 (мови не вміє), HolosTTS (штучний), OmniVoice (клон зі стороннього запису переймає
його паузи й рівну подачу).

## LoRA для Flux3 Klein

LoRA для FLUX.2 Klein не працюють із Flux3 Klein: інша архітектура, шари не збігаються, ComfyUI пише `lora key not loaded`, а кадр лишається піксель у піксель без змін. Після підключення будь-якої LoRA порівнювати кадр із запуском без неї на тому самому сиді.

Файли — у `models/loras/flux3/` спільної теки Comfy Desktop, підключення: `ref_compare.py --lora ім'я:сила`. LoRA на позу чи анатомію під Flux3 поки немає.

| LoRA | Результат |
|---|---|
| `photo50k_comfy` ([SimpleTuner photo-aesthetics](https://huggingface.co/SimpleTuner/flux-3-klein-image-photo-aesthetics-distilled-assisted-1024-v2-kohaku-fa), checkpoint-50000) | Основна для живого фото, сила 1. Оригінал має ключі diffusers і мовчки не діє: перейменувати `transformer.mode_blocks.` → `diffusion_model.content_mode_blocks.`, `transformer.txt_mode_blocks.` → `diffusion_model.txt_mode_blocks.`, `transformer.single_transformer_blocks.` → `diffusion_model.single_blocks.`, `.attn.to_k/q/v.` → `.k_proj/q_proj/v_proj.`, `.attn.to_out.0.` → `.attn_out.`. Ліцензія некомерційна |
| `skin10000` ([natural facial skin texture](https://huggingface.co/yang520333/Flux3-image-klein-natural-facial-skin-texture-lora)) | Діє, сила 0.6, але на кадрі без обличчя виграшу не дає |
| `consistency`, `angles` | Завантажені, не перевірялись. Consistency — для правок кадру, angles — для зміни ракурсу |
| Tattoo (Civitai 3027588) | Не завантажено: Civitai віддає файл лише з API-ключем |

**Складна поза — новий кадр, а не правки.** Шість правок поверх невдалого кадру лише зберігали його помилки. Генерація з нуля через Flux3 з референсом людини (~20 с) і 12–18 сидів дала правильні руки й місце тату. Людину, яку не можна впізнати, ховай відблиском: `python tools/flash_glare.py SRC DST X Y`. Тату чи іншу деталь із точним місцем і кутом модель не ставить: зітри її правкою, а малюнок наклади `tools/tattoo_place.py` за координатами, той самий на всіх кадрах.
