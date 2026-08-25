import json
import os
import struct
import unicodedata

def generate_nfc_table():
    entries = []
    for cp in range(0x110000):
        try:
            ch = chr(cp)
            normalized = unicodedata.normalize('NFC', ch)
            if normalized != ch:
                entries.append((cp, normalized.encode('utf-8')))
        except (ValueError, OverflowError):
            pass
    return entries

def generate_nfkc_table():
    entries = []
    for cp in range(0x110000):
        try:
            ch = chr(cp)
            normalized = unicodedata.normalize('NFKC', ch)
            if normalized != ch:
                entries.append((cp, normalized.encode('utf-8')))
        except (ValueError, OverflowError):
            pass
    return entries

def generate_nfd_table():
    entries = []
    for cp in range(0x110000):
        try:
            ch = chr(cp)
            decomposed = unicodedata.normalize('NFD', ch)
            if decomposed != ch:
                entries.append((cp, decomposed.encode('utf-8')))
        except (ValueError, OverflowError):
            pass
    return entries

def write_norm_table(fp, entries):
    fp.write(struct.pack('<I', len(entries)))
    for cp, utf8 in entries:
        fp.write(struct.pack('<I', cp))
        fp.write(struct.pack('<H', len(utf8)))
        fp.write(utf8)

def pack_str(s):
    if isinstance(s, str):
        s = s.encode('utf-8')
    return struct.pack('<H', len(s)) + s

def export_mtok(tokenizer_json_path, output_mtok_path):
    with open(tokenizer_json_path, 'r', encoding='utf-8') as f:
        tj = json.load(f)

    MAGIC_NUMBER = 430
    PIPELINE = 4

    special_list = []
    if 'added_tokens' in tj:
        for at in tj['added_tokens']:
            if at.get('special', False):
                special_list.append(at['id'])
    # Stable Diffusion 1.5 CLIP special tokens: BOS=49406, EOS=49407
    if 49406 not in special_list:
        special_list.append(49406)
    if 49407 not in special_list:
        special_list.append(49407)
    stop_ids = [49407]
    prefix_list = []

    with open(output_mtok_path, "w", encoding="utf8") as fp:
        fp.write(f'{MAGIC_NUMBER} {PIPELINE}\n')
        fp.write(f'{len(special_list)} {len(stop_ids)} {len(prefix_list)}\n')
        tokens_line = ' '.join(str(t) for t in (special_list + stop_ids + prefix_list))
        fp.write(tokens_line + '\n' if tokens_line else '\n')

    with open(output_mtok_path, "ab") as fp:
        # --- Normalizer ---
        norm = tj.get('normalizer')
        def write_normalizer_bin(fp, norm):
            if norm is None:
                fp.write(struct.pack('<B', 0))
                return
            ntype = norm.get('type', '')
            if ntype in ('NFKC', 'Precompiled', 'NFKD'):
                fp.write(struct.pack('<B', 6))
                write_norm_table(fp, generate_nfkc_table())
            elif ntype == 'NFC':
                fp.write(struct.pack('<B', 6))
                write_norm_table(fp, generate_nfc_table())
            elif ntype == 'Prepend':
                fp.write(struct.pack('<B', 2))
                fp.write(pack_str(norm.get('prepend', '')))
            elif ntype == 'Replace':
                fp.write(struct.pack('<B', 3))
                pattern = ''
                if isinstance(norm.get('pattern'), dict):
                    pattern = norm['pattern'].get('Regex', norm['pattern'].get('String', ''))
                elif isinstance(norm.get('pattern'), str):
                    pattern = norm['pattern']
                fp.write(pack_str(pattern))
                fp.write(pack_str(norm.get('content', '')))
            elif ntype == 'Sequence':
                fp.write(struct.pack('<B', 4))
                normalizers = norm.get('normalizers', [])
                fp.write(struct.pack('<I', len(normalizers)))
                for n in normalizers:
                    write_normalizer_bin(fp, n)
            elif ntype == 'BertNormalizer':
                sa = norm.get('strip_accents', False)
                if sa is None and norm.get('lowercase', True):
                    sa = True
                strip_accents = int(sa or False)
                if strip_accents:
                    fp.write(struct.pack('<B', 7))
                else:
                    fp.write(struct.pack('<B', 5))
                fp.write(struct.pack('<BBBB',
                    int(norm.get('clean_text', True)),
                    int(norm.get('handle_chinese_chars', True)),
                    strip_accents,
                    int(norm.get('lowercase', True))))
                if strip_accents:
                    write_norm_table(fp, generate_nfd_table())
            elif ntype == 'Lowercase':
                fp.write(struct.pack('<B', 5))
                fp.write(struct.pack('<BBBB', 0, 0, 0, 1))
            elif ntype == 'StripAccents':
                fp.write(struct.pack('<B', 7))
                fp.write(struct.pack('<BBBB', 0, 0, 1, 0))
                write_norm_table(fp, generate_nfd_table())
            elif ntype == 'Strip':
                fp.write(struct.pack('<B', 8))
                fp.write(struct.pack('<BB',
                    int(norm.get('strip_left', True)),
                    int(norm.get('strip_right', True))))
            else:
                fp.write(struct.pack('<B', 0))
        write_normalizer_bin(fp, norm)

        # --- PreTokenizer ---
        pt = tj.get('pre_tokenizer')
        def write_pre_tokenizer_bin(fp, pt):
            if pt is None:
                fp.write(struct.pack('<B', 0))
                return
            ptype = pt.get('type', '')
            if ptype == 'ByteLevel':
                fp.write(struct.pack('<BB', 1, int(pt.get('use_regex', True))))
            elif ptype == 'Digits':
                fp.write(struct.pack('<BB', 2, int(pt.get('individual_digits', False))))
            elif ptype == 'Metaspace':
                fp.write(struct.pack('<B', 3))
                rep = pt.get('replacement', '\u2581')
                if pt.get('str_rep'):
                    rep = pt['str_rep']
                fp.write(pack_str(rep))
                fp.write(struct.pack('<B', int(pt.get('add_prefix_space', True))))
            elif ptype == 'Split':
                fp.write(struct.pack('<B', 4))
                pattern = ''
                if isinstance(pt.get('pattern'), dict):
                    pattern = pt['pattern'].get('Regex', pt['pattern'].get('String', ''))
                elif isinstance(pt.get('pattern'), str):
                    pattern = pt['pattern']
                fp.write(pack_str(pattern))
                behavior = pt.get('behavior', 'Isolated')
                behavior_id = 0 if behavior == 'Isolated' else (2 if behavior == 'MergedWithPrevious' else 1)
                fp.write(struct.pack('<BB', int(pt.get('invert', False)), behavior_id))
            elif ptype == 'BertPreTokenizer':
                fp.write(struct.pack('<B', 5))
            elif ptype == 'Sequence':
                fp.write(struct.pack('<B', 6))
                pretokenizers = pt.get('pretokenizers', [])
                fp.write(struct.pack('<I', len(pretokenizers)))
                for p in pretokenizers:
                    write_pre_tokenizer_bin(fp, p)
            elif ptype == 'WhitespaceSplit':
                fp.write(struct.pack('<B', 4))
                fp.write(pack_str('\\s+'))
                fp.write(struct.pack('<BB', 0, 1))
            else:
                fp.write(struct.pack('<B', 0))
        write_pre_tokenizer_bin(fp, pt)

        # --- Model ---
        model = tj.get('model', {})
        vocab = model.get('vocab', {})
        merges = model.get('merges', [])
        byte_fallback = int(model.get('byte_fallback', False))
        byte_level = 0
        if pt and pt.get('type') == 'ByteLevel':
            byte_level = 0
        elif pt and pt.get('type') == 'Sequence':
            has_bl_pt = any(p.get('type') == 'ByteLevel' for p in pt.get('pretokenizers', []))
            if not has_bl_pt:
                dec = tj.get('decoder')
                if dec and dec.get('type') == 'ByteLevel':
                    byte_level = 1
                elif dec and dec.get('type') == 'Sequence':
                    if any(d.get('type') == 'ByteLevel' for d in dec.get('decoders', [])):
                        byte_level = 1

        sorted_vocab = sorted(vocab.items(), key=lambda x: x[0])
        vocab_size = len(sorted_vocab)

        fp.write(struct.pack('<B', 0))  # type=BPE
        fp.write(struct.pack('<I', vocab_size))
        fp.write(struct.pack('<BB', byte_fallback, byte_level))
        fp.write(struct.pack('<I', len(merges)))

        for token, tid in sorted_vocab:
            fp.write(pack_str(token))
            fp.write(struct.pack('<I', tid))

        merge_pairs = []
        for i, m in enumerate(merges):
            if isinstance(m, str):
                parts = m.split(' ', 1)
                if len(parts) == 2:
                    id1 = vocab.get(parts[0], -1)
                    id2 = vocab.get(parts[1], -1)
                    merge_pairs.append((id1, id2, i))
            elif isinstance(m, list) and len(m) >= 2:
                id1 = vocab.get(m[0], -1)
                id2 = vocab.get(m[1], -1)
                merge_pairs.append((id1, id2, i))
        merge_pairs.sort(key=lambda x: (x[0] << 32) | (x[1] & 0xFFFFFFFF))
        for id1, id2, rank in merge_pairs:
            fp.write(struct.pack('<III', id1, id2, rank))

        # CLIP's BPE seeds each word's merge with a trailing "</w>" (see model.end_of_word_suffix
        # in tokenizer.json) before running merges, so e.g. "apple" resolves to the word-final
        # vocab entry "apple</w>" instead of the mid-word entry "apple" -- a different id with a
        # different, untrained-in-context embedding. Without this the reader has no way to know
        # a suffix is expected at all.
        fp.write(pack_str(model.get('end_of_word_suffix', '') or ''))
        fp.write(pack_str(model.get('continuing_subword_prefix', '') or ''))

        # --- Decoder ---
        dec = tj.get('decoder')
        def write_decoder_bin(fp, dec):
            if dec is None:
                fp.write(struct.pack('<B', 0))
                return
            dtype = dec.get('type', '')
            if dtype == 'ByteLevel':
                fp.write(struct.pack('<B', 0))
            elif dtype == 'ByteFallback':
                fp.write(struct.pack('<B', 1))
            elif dtype == 'Metaspace':
                fp.write(struct.pack('<B', 2))
                fp.write(pack_str(dec.get('replacement', '\u2581')))
                fp.write(struct.pack('<B', int(dec.get('add_prefix_space', True))))
            elif dtype == 'WordPiece':
                fp.write(struct.pack('<B', 3))
                fp.write(pack_str(dec.get('prefix', '##')))
                fp.write(struct.pack('<B', int(dec.get('cleanup', True))))
            elif dtype == 'Fuse':
                fp.write(struct.pack('<B', 4))
            elif dtype == 'Replace':
                fp.write(struct.pack('<B', 5))
                pattern = ''
                if isinstance(dec.get('pattern'), dict):
                    pattern = dec['pattern'].get('String', '')
                elif isinstance(dec.get('pattern'), str):
                    pattern = dec['pattern']
                fp.write(pack_str(pattern))
                fp.write(pack_str(dec.get('content', '')))
            elif dtype == 'Strip':
                fp.write(struct.pack('<B', 6))
                fp.write(pack_str(dec.get('content', '')))
                fp.write(struct.pack('<II', dec.get('start', 0), dec.get('stop', 0)))
            elif dtype == 'Sequence':
                fp.write(struct.pack('<B', 7))
                decoders = dec.get('decoders', [])
                fp.write(struct.pack('<I', len(decoders)))
                for d in decoders:
                    write_decoder_bin(fp, d)
            else:
                fp.write(struct.pack('<B', 0))
        write_decoder_bin(fp, dec)

        # --- Added Tokens ---
        added_tokens = tj.get('added_tokens', [])
        fp.write(struct.pack('<I', len(added_tokens)))
        for at in added_tokens:
            aid = at.get('id', -1)
            special = int(at.get('special', False))
            lstrip = int(at.get('lstrip', False))
            rstrip = int(at.get('rstrip', False))
            content = at.get('content', '')
            fp.write(struct.pack('<I', aid))
            fp.write(struct.pack('<BBB', special, lstrip, rstrip))
            fp.write(pack_str(content))

        # --- Chat Template & Flags ---
        chat_template = ''
        eos_token = '<|endoftext|>'
        bos_token = '<|startoftext|>'
        flags = 0
        tpl_bytes = chat_template.encode('utf-8')
        eos_bytes = eos_token.encode('utf-8')
        fp.write(struct.pack('<I', len(tpl_bytes)))
        fp.write(tpl_bytes)
        fp.write(struct.pack('<H', len(eos_bytes)))
        fp.write(eos_bytes)
        fp.write(struct.pack('<B', flags))
        bos_bytes = bos_token.encode('utf-8')
        fp.write(struct.pack('<H', len(bos_bytes)))
        fp.write(bos_bytes)

    print(f'Successfully exported tokenizer.mtok: {output_mtok_path} (size: {os.path.getsize(output_mtok_path)} bytes)')

if __name__ == '__main__':
    src = '/Users/alpha/mobile-inference/tools/tokenizer_export/tokenizer.json'
    dst = '/Users/alpha/mobile-inference/tools/tokenizer_export/tokenizer.mtok'
    export_mtok(src, dst)
