import unicodedata


def pad(text: str, width: int) -> str:
    """한글(전각) 문자를 2칸으로 계산해 왼쪽 정렬한다."""
    w = sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in text)
    return text + " " * max(0, width - w)
