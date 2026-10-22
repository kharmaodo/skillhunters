"""CPU-only embeddings: exact ONNX artifact, masked mean pooling and L2 normalization."""
import hashlib
import json
import os
from pathlib import Path

os.environ['TOKENIZERS_PARALLELISM'] = 'false'
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


class Engine:
    def __init__(self, directory):
        self.manifest = json.loads(Path(__file__).with_name('manifest.json').read_text())
        root = Path(directory)
        for name, info in self.manifest['files'].items():
            if hashlib.sha256((root / name).read_bytes()).hexdigest() != info['sha256']:
                raise ValueError('MODEL_HASH')
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(root / 'model.onnx'), sess_options=options, providers=['CPUExecutionProvider'])
        self.tokenizer = Tokenizer.from_file(str(root / 'tokenizer.json'))
        self.tokenizer.no_truncation()
        self.tokenizer.enable_padding(pad_id=0, pad_token='[PAD]')

    def encode(self, texts):
        if not isinstance(texts, list) or not 1 <= len(texts) <= 128:
            raise ValueError('INPUT_LIMIT')
        if any(not isinstance(t, str) or not t.strip() or len(t) > 200 or '@' in t or any(ord(c) < 32 for c in t) for t in texts):
            raise ValueError('INPUT_LABEL')
        vectors = []
        for start in range(0, len(texts), 8):
            batch = self.tokenizer.encode_batch(texts[start:start + 8])
            if any(len(item.ids) > 128 for item in batch):
                raise ValueError('TOKEN_LIMIT')
            inputs = {
                'input_ids': np.array([item.ids for item in batch], dtype=np.int64),
                'attention_mask': np.array([item.attention_mask for item in batch], dtype=np.int64),
                'token_type_ids': np.array([item.type_ids for item in batch], dtype=np.int64),
            }
            inputs = {item.name: inputs[item.name] for item in self.session.get_inputs()}
            tokens = self.session.run(None, inputs)[0]
            mask = inputs['attention_mask'][..., None]
            pooled = (tokens * mask).sum(axis=1) / np.maximum(mask.sum(axis=1), 1)
            norms = np.linalg.norm(pooled, axis=1, keepdims=True)
            if not np.isfinite(pooled).all() or (norms < 1e-9).any() or pooled.shape[1] != 384:
                raise ValueError('MODEL_OUTPUT')
            vectors.extend((pooled / norms).tolist())
        return {'model': self.manifest['model'], 'vectors': vectors}
