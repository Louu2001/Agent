from typing import List

from fastapi import FastAPI
from FlagEmbedding import FlagReranker
from pydantic import BaseModel


MODEL_NAME = "BAAI/bge-reranker-v2-m3"

app = FastAPI(title="BGE Rerank Server")
reranker = FlagReranker(MODEL_NAME, use_fp16=False)


class RerankRequest(BaseModel):
    query: str
    texts: List[str]
    raw_scores: bool = False


@app.post("/rerank")
def rerank(request: RerankRequest):
    pairs = [[request.query, text] for text in request.texts]
    scores = reranker.compute_score(pairs, normalize=not request.raw_scores)
    if not isinstance(scores, list):
        scores = [scores]
    return [
        {
            "index": index,
            "score": float(score),
        }
        for index, score in enumerate(scores)
    ]
