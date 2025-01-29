#!/bin/bash

# Задайте необходимые переменные
GOCD_API_URL="http://172.16.19.155:8153/go"
PIPELINE_NAME="Spot-adapter"
CURRENT_COUNTER=22  # Замените на текущий номер экземпляра пайплайна
ACCESS_TOKEN="c1d5bf73fb9f77bca592d642c8e9e990640a8eb8"  # Вставьте сюда ваш персональный токен доступа

# Вычисляем номер предыдущего экземпляра
PREVIOUS_COUNTER=$((CURRENT_COUNTER - 1))

# Формируем URL для запроса информации о предыдущем экземпляре пайплайна
API_URL="${GOCD_API_URL}/api/pipelines/${PIPELINE_NAME}/instance/${PREVIOUS_COUNTER}"

# Выполняем запрос к API GoCD с использованием токена доступа
echo "Запрос к API: $API_URL"
response=$(curl -s -H "Accept: application/json" -H "Authorization: Bearer ${ACCESS_TOKEN}" "$API_URL")

# Проверяем, удалось ли получить ответ
if [ -z "$response" ]; then
  echo "Не удалось получить данные от GoCD API."
  exit 1
fi

# Отладочный вывод полного JSON-ответа
echo "JSON-ответ от API:"
echo "$response" | jq .

# Извлекаем информацию о стадиях из ответа
stages=$(echo "$response" | jq -r '.stages[]? | {name: .name, result: .result}')

# Проверяем, если stages отсутствует или пусто
if [ -z "$stages" ]; then
  echo "Нет данных о стадиях в предыдущем запуске пайплайна."
  exit 1
fi

# Проверяем статус каждой стадии
overall_status="Passed"
while IFS= read -r stage; do
  stage_name=$(echo "$stage" | jq -r '.name')
  stage_result=$(echo "$stage" | jq -r '.result')

  echo "Стадия: $stage_name, Результат: $stage_result"

  if [ "$stage_result" == "Failed" ]; then
    overall_status="Failed"
    break
  elif [ "$stage_result" == "Cancelled" ]; then
    overall_status="Cancelled"
  fi
done <<< "$stages"

# Вывод общего статуса
echo "Общий статус предыдущего запуска пайплайна: $overall_status"