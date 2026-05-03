#!/usr/bin/env python3
"""
Google Takeout EXIF Restoration Tool — tkinter desktop UI (legacy)
The primary UI is now the browser-based web_app.py.

Requires ExifTool: https://exiftool.org
  Linux:   sudo apt install libimage-exiftool-perl
  macOS:   brew install exiftool
  Windows: download installer from https://exiftool.org
"""

import os
import subprocess
import threading
import platform
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from pathlib import Path
from typing import Optional, List

from core import (
    _SYS, ALL_MEDIA,
    Stats, Meta, DupMatch,
    _check_exiftool, _fmt_size, _ts_from_filename,
    find_duplicates, Processor,
)

from datetime import datetime, timezone

# ── GUI helpers ───────────────────────────────────────────────────────────────

_UI   = 'Segoe UI'   if _SYS == 'Windows' else ('SF Pro Text'    if _SYS == 'Darwin' else 'Ubuntu')
_MONO = 'Consolas'   if _SYS == 'Windows' else ('SF Mono'        if _SYS == 'Darwin' else 'DejaVu Sans Mono')

C = {
    'bg':      '#F1F3F4',
    'card':    '#FFFFFF',
    'border':  '#DADCE0',
    'primary': '#1A73E8',
    'success': '#188038',
    'warn':    '#E37400',
    'error':   '#C5221F',
    'text':    '#202124',
    'muted':   '#5F6368',
    'hdr_bg':  '#1A73E8',
    'hdr_fg':  '#FFFFFF',
    'hdr_sub': '#BDD7FF',
    'log_bg':  '#1E1E2E',
    'log_fg':  '#CDD6F4',
}


class FolderRow(tk.Frame):
    """Label + Entry + Browse button for folder selection."""

    def __init__(self, parent, label: str, **kw):
        super().__init__(parent, bg=C['card'], **kw)
        tk.Label(self, text=label, width=9, anchor='w',
                 bg=C['card'], fg=C['muted'],
                 font=(_UI, 9, 'bold')).pack(side='left', padx=(0, 8))

        self._var = tk.StringVar()
        self._entry = tk.Entry(
            self, textvariable=self._var,
            font=(_UI, 10), bg=C['bg'], fg=C['text'],
            relief='solid', bd=1,
            highlightthickness=1,
            highlightbackground=C['border'],
            highlightcolor=C['primary'],
        )
        self._entry.pack(side='left', fill='x', expand=True, ipady=5)

        tk.Button(
            self, text='Browse…', command=self._browse,
            bg=C['primary'], fg='white', relief='flat',
            font=(_UI, 9), padx=14, pady=6,
            activebackground='#1557B0', activeforeground='white',
            cursor='hand2',
        ).pack(side='left', padx=(8, 0))

    def _browse(self):
        d = filedialog.askdirectory(title='Select folder')
        if d:
            self._var.set(d)

    def get(self) -> str:
        return self._var.get().strip()


class StatCard(tk.Frame):
    def __init__(self, parent, label: str, color: str, **kw):
        super().__init__(parent, bg=C['card'],
                         highlightbackground=C['border'],
                         highlightthickness=1, **kw)
        self._var = tk.StringVar(value='0')
        tk.Label(self, textvariable=self._var,
                 font=(_UI, 26, 'bold'), fg=color, bg=C['card']
                 ).pack(pady=(12, 2))
        tk.Label(self, text=label,
                 font=(_UI, 8), fg=C['muted'], bg=C['card']
                 ).pack(pady=(0, 12))

    def set(self, v: int):
        self._var.set(str(v))


class DuplicateReviewWindow(tk.Toplevel):
    def __init__(self, parent, matches: List[DupMatch], src_root: Path):
        super().__init__(parent)
        n = len(matches)
        self.title(f'Duplicates Found — {n} pair{"s" if n != 1 else ""}')
        self.geometry('860x540')
        self.minsize(640, 400)
        self.configure(bg=C['bg'])
        self.transient(parent)
        self.grab_set()
        self.resizable(True, True)

        self._matches   = matches
        self._src_root  = src_root
        self._action: Optional[str] = None

        self._build_ui()
        self._populate()

        self.protocol('WM_DELETE_WINDOW', self._on_cancel)
        self.update_idletasks()
        px, py = parent.winfo_x(), parent.winfo_y()
        pw, ph = parent.winfo_width(), parent.winfo_height()
        w,  h  = self.winfo_width(), self.winfo_height()
        self.geometry(f'+{px + (pw - w) // 2}+{py + (ph - h) // 2}')
        self.wait_window()

    @property
    def action(self) -> Optional[str]:
        return self._action

    def _build_ui(self):
        n_exact  = sum(1 for m in self._matches if m.confidence == 'exact')
        n_strong = len(self._matches) - n_exact

        hdr = tk.Frame(self, bg='#BF360C')
        hdr.pack(fill='x')
        tk.Label(hdr, text='\U0001f50d  Duplicate Files Found',
                 font=(_UI, 12, 'bold'), fg='white', bg='#BF360C'
                 ).pack(side='left', padx=18, pady=12)
        tk.Label(hdr,
                 text=f'{len(self._matches)} pair(s)  ·  '
                      f'{n_exact} identical (MD5)  ·  '
                      f'{n_strong} same name + size',
                 font=(_UI, 9), fg='#FFCCBC', bg='#BF360C'
                 ).pack(side='left', pady=12)

        info_card = tk.Frame(self, bg=C['card'],
                             highlightbackground=C['border'], highlightthickness=1)
        info_card.pack(fill='x', padx=14, pady=(12, 0))
        tk.Label(
            info_card,
            text='"Skip Duplicates" keeps Copy 1 and skips Copy 2 for every pair.\n'
                 '"Keep All Copies" processes every file as normal.',
            font=(_UI, 9), fg=C['muted'], bg=C['card'],
            justify='left', wraplength=820,
        ).pack(padx=14, pady=10, anchor='w')

        tree_wrap = tk.Frame(self, bg=C['bg'])
        tree_wrap.pack(fill='both', expand=True, padx=14, pady=(10, 0))

        cols = ('copy1', 'copy2', 'size', 'match')
        self._tree = ttk.Treeview(tree_wrap, columns=cols,
                                  show='headings', selectmode='browse')
        col_specs = [
            ('copy1', 'Copy 1 — kept',             310, 'w'),
            ('copy2', 'Copy 2 — skipped if dedup', 310, 'w'),
            ('size',  'Size',                        80, 'e'),
            ('match', 'Match',                       90, 'center'),
        ]
        for cid, head, w, anc in col_specs:
            self._tree.heading(cid, text=head)
            self._tree.column(cid, width=w, minwidth=60, anchor=anc)

        vsb = ttk.Scrollbar(tree_wrap, orient='vertical', command=self._tree.yview)
        self._tree.configure(yscrollcommand=vsb.set)
        vsb.pack(side='right', fill='y')
        self._tree.pack(fill='both', expand=True)
        self._tree.tag_configure('exact',  background='#FFF3E0')
        self._tree.tag_configure('strong', background='#F3E5F5')

        btn_bar = tk.Frame(self, bg=C['bg'])
        btn_bar.pack(fill='x', padx=14, pady=12)
        tk.Button(btn_bar, text='✓  Skip Duplicates  (keep Copy 1 only)',
                  command=self._on_skip,
                  bg=C['success'], fg='white', relief='flat',
                  font=(_UI, 10, 'bold'), padx=20, pady=9,
                  activebackground='#145C2C', activeforeground='white',
                  cursor='hand2').pack(side='left', padx=(0, 10))
        tk.Button(btn_bar, text='Keep All Copies',
                  command=self._on_keep_all,
                  bg=C['primary'], fg='white', relief='flat',
                  font=(_UI, 10, 'bold'), padx=20, pady=9,
                  activebackground='#1557B0', activeforeground='white',
                  cursor='hand2').pack(side='left', padx=(0, 10))
        tk.Button(btn_bar, text='✕  Cancel',
                  command=self._on_cancel,
                  bg=C['bg'], fg=C['error'], relief='solid', bd=1,
                  font=(_UI, 10), padx=16, pady=9, cursor='hand2').pack(side='left')

    def _populate(self):
        for m in self._matches:
            try:
                a_rel = str(m.file_a.relative_to(self._src_root))
            except ValueError:
                a_rel = m.file_a.name
            try:
                b_rel = str(m.file_b.relative_to(self._src_root))
            except ValueError:
                b_rel = m.file_b.name
            conf = '✓✓ exact' if m.confidence == 'exact' else '✓ strong'
            self._tree.insert('', 'end',
                              values=(a_rel, b_rel, _fmt_size(m.size), conf),
                              tags=(m.confidence,))

    def _on_skip(self):     self._action = 'skip_dupes'; self.destroy()
    def _on_keep_all(self): self._action = 'keep_all';  self.destroy()
    def _on_cancel(self):   self._action = None;        self.destroy()


class App(tk.Tk):

    def __init__(self):
        super().__init__()
        self.title('Google Takeout — EXIF Restoration Tool')
        self.geometry('960x740')
        self.minsize(740, 580)
        self.configure(bg=C['bg'])
        self._processor: Optional[Processor] = None
        self._thread:    Optional[threading.Thread] = None
        self._build_ui()
        self.after(200, self._startup_check)

    # ── Layout builders ───────────────────────────────────────────────────

    def _card_frame(self, parent, pady=(0, 10)):
        outer = tk.Frame(parent, bg=C['card'],
                         highlightbackground=C['border'],
                         highlightthickness=1)
        outer.pack(fill='x', padx=20, pady=pady)
        inner = tk.Frame(outer, bg=C['card'])
        inner.pack(fill='x', padx=18, pady=14)
        return inner

    def _section_label(self, parent, text: str):
        tk.Label(parent, text=text, font=(_UI, 10, 'bold'),
                 fg=C['text'], bg=C['card']).pack(anchor='w', pady=(0, 10))

    def _build_ui(self):
        self._build_header()
        self._build_folders()
        self._build_options()
        self._build_controls()
        self._build_stats()
        self._build_progress()
        self._build_log()

    def _build_header(self):
        hdr = tk.Frame(self, bg=C['hdr_bg'])
        hdr.pack(fill='x')
        tk.Label(hdr, text='\U0001f4f7  Google Takeout EXIF Restoration',
                 font=(_UI, 14, 'bold'), fg=C['hdr_fg'], bg=C['hdr_bg']
                 ).pack(side='left', padx=20, pady=16)
        tk.Label(hdr,
                 text='Restores original dates, times & GPS from JSON sidecars',
                 font=(_UI, 9), fg=C['hdr_sub'], bg=C['hdr_bg']
                 ).pack(side='left', pady=16)

    def _build_folders(self):
        inner = self._card_frame(self, pady=(16, 10))
        self._section_label(inner, 'Folders')
        self._src_row = FolderRow(inner, 'Source')
        self._src_row.pack(fill='x', pady=(0, 8))
        self._dst_row = FolderRow(inner, 'Output')
        self._dst_row.pack(fill='x')

    def _build_options(self):
        inner = self._card_frame(self)
        self._section_label(inner, 'Options')

        self._copy_unmatched = tk.BooleanVar(value=True)
        tk.Checkbutton(
            inner,
            text='Copy files that have no matching JSON sidecar (unchanged)',
            variable=self._copy_unmatched,
            font=(_UI, 9), fg=C['muted'], bg=C['card'],
            activebackground=C['card'], selectcolor=C['bg'],
        ).pack(anchor='w', pady=(0, 10))

        tk.Label(inner, text='Output folder organisation:',
                 font=(_UI, 9, 'bold'), fg=C['text'],
                 bg=C['card']).pack(anchor='w', pady=(0, 4))

        self._output_mode = tk.StringVar(value='date')
        modes = [
            ('date',     'Organise by date  —  output / 2021 / 01 / 15 / photo.jpg'),
            ('preserve', 'Preserve original folder structure'),
            ('flat',     'Flat  —  all files in one folder'),
        ]
        for value, label in modes:
            tk.Radiobutton(
                inner, text=label, value=value,
                variable=self._output_mode,
                font=(_UI, 9), fg=C['muted'], bg=C['card'],
                activebackground=C['card'], selectcolor=C['bg'],
            ).pack(anchor='w')

        tk.Frame(inner, bg=C['border'], height=1).pack(fill='x', pady=(14, 0))
        self._check_dupes = tk.BooleanVar(value=False)
        tk.Checkbutton(
            inner,
            text='Scan for duplicate files before processing',
            variable=self._check_dupes,
            font=(_UI, 9), fg=C['muted'], bg=C['card'],
            activebackground=C['card'], selectcolor=C['bg'],
        ).pack(anchor='w', pady=(8, 0))

    def _build_controls(self):
        inner = self._card_frame(self, pady=(0, 10))
        row = tk.Frame(inner, bg=C['card'])
        row.pack(fill='x')

        self._btn_start = tk.Button(
            row, text='▶  Start Processing', command=self._on_start,
            bg=C['success'], fg='white', relief='flat',
            font=(_UI, 10, 'bold'), padx=20, pady=9,
            activebackground='#145C2C', activeforeground='white',
            cursor='hand2',
        )
        self._btn_start.pack(side='left', padx=(0, 8))

        self._btn_stop = tk.Button(
            row, text='■  Stop', command=self._on_stop,
            bg=C['error'], fg='white', relief='flat',
            font=(_UI, 10, 'bold'), padx=20, pady=9,
            activebackground='#922018', activeforeground='white',
            cursor='hand2', state='disabled',
        )
        self._btn_stop.pack(side='left', padx=(0, 8))

        tk.Button(
            row, text='\U0001f4c2  Open Output Folder',
            command=self._on_open_output,
            bg=C['bg'], fg=C['primary'], relief='solid', bd=1,
            font=(_UI, 10), padx=16, pady=9, cursor='hand2',
        ).pack(side='left')

    def _build_stats(self):
        inner = self._card_frame(self)
        self._stat_total  = StatCard(inner, 'TOTAL',   C['primary'])
        self._stat_fixed  = StatCard(inner, 'FIXED',   C['success'])
        self._stat_nojson = StatCard(inner, 'NO JSON', C['warn'])
        self._stat_errors = StatCard(inner, 'ERRORS',  C['error'])
        for i, card in enumerate((self._stat_total, self._stat_fixed,
                                   self._stat_nojson, self._stat_errors)):
            card.pack(side='left', fill='x', expand=True,
                      padx=(0, 0 if i == 3 else 8))

    def _build_progress(self):
        inner = self._card_frame(self, pady=(0, 10))
        row_top = tk.Frame(inner, bg=C['card'])
        row_top.pack(fill='x', pady=(0, 4))
        self._prog_label = tk.Label(
            row_top, text='Ready — select folders and press Start',
            font=(_UI, 9), fg=C['muted'], bg=C['card'], anchor='w',
        )
        self._prog_label.pack(side='left', fill='x', expand=True)

        style = ttk.Style()
        style.theme_use('clam')
        style.configure('G.Horizontal.TProgressbar',
                        troughcolor=C['border'],
                        background=C['primary'],
                        borderwidth=0, thickness=12)
        self._progress = ttk.Progressbar(
            inner, style='G.Horizontal.TProgressbar', mode='determinate',
        )
        self._progress.pack(fill='x')
        self._prog_file = tk.Label(
            inner, text='', font=(_MONO, 8), fg=C['muted'], bg=C['card'], anchor='w',
        )
        self._prog_file.pack(anchor='w', pady=(4, 0))

    def _build_log(self):
        wrapper = tk.Frame(self, bg=C['bg'])
        wrapper.pack(fill='both', expand=True, padx=20, pady=(0, 18))
        tk.Label(wrapper, text='Log', font=(_UI, 10, 'bold'),
                 fg=C['text'], bg=C['bg']).pack(anchor='w', pady=(0, 4))
        border = tk.Frame(wrapper, bg=C['log_bg'],
                          highlightbackground=C['border'], highlightthickness=1)
        border.pack(fill='both', expand=True)
        self._log = tk.Text(
            border, bg=C['log_bg'], fg=C['log_fg'],
            font=(_MONO, 9), relief='flat', bd=0,
            state='disabled', wrap='none', insertbackground=C['log_fg'],
        )
        vsb = ttk.Scrollbar(border, orient='vertical', command=self._log.yview)
        self._log.configure(yscrollcommand=vsb.set)
        vsb.pack(side='right', fill='y')
        self._log.pack(side='left', fill='both', expand=True, padx=6, pady=6)
        self._log.tag_config('info',  foreground='#89B4FA')
        self._log.tag_config('ok',    foreground='#A6E3A1')
        self._log.tag_config('warn',  foreground='#FAB387')
        self._log.tag_config('error', foreground='#F38BA8')
        self._log.tag_config('file',  foreground='#CDD6F4')

    # ── Startup ───────────────────────────────────────────────────────────

    def _startup_check(self):
        def _run():
            ver = _check_exiftool()
            if ver:
                self._log_msg('ok', f'ExifTool v{ver} — ready.')
            else:
                self._log_msg('error', 'ExifTool not found — install it before processing:')
                if _SYS == 'Windows':
                    self._log_msg('error', '  Windows : download from https://exiftool.org')
                elif _SYS == 'Darwin':
                    self._log_msg('error', '  macOS   : brew install exiftool')
                else:
                    self._log_msg('error', '  Linux   : sudo apt install libimage-exiftool-perl')
        threading.Thread(target=_run, daemon=True).start()

    # ── Callbacks ─────────────────────────────────────────────────────────

    def _log_msg(self, level: str, text: str):
        def _do():
            self._log.configure(state='normal')
            self._log.insert('end', text + '\n', level)
            self._log.see('end')
            self._log.configure(state='disabled')
        self.after(0, _do)

    def _update_progress(self, current: int, total: int,
                         current_file: str = '', fps: float = 0.0):
        def _do():
            pct = int(current / total * 100) if total else 0
            self._progress['value'] = pct
            speed = f'  ·  {fps:.1f} files/s' if fps > 0.1 else ''
            self._prog_label.config(
                text=f'Processing {current} of {total}  ({pct}%){speed}'
            )
            name = Path(current_file).name if current_file else ''
            self._prog_file.config(text=name if name else '')
        self.after(0, _do)

    def _update_stats(self, stats: Stats):
        def _do():
            self._stat_total.set(stats.total)
            self._stat_fixed.set(stats.processed)
            self._stat_nojson.set(stats.no_json)
            self._stat_errors.set(stats.errors)
        self.after(0, _do)

    def _on_done(self, ok: bool):
        def _do():
            self._btn_start.config(state='normal')
            self._btn_stop.config(state='disabled')
            if ok:
                self._prog_label.config(text='Done ✔')
                self._prog_file.config(text='')
        self.after(0, _do)

    # ── Button handlers ───────────────────────────────────────────────────

    def _on_start(self):
        src = self._src_row.get()
        dst = self._dst_row.get()

        if not src:
            messagebox.showerror('No Source Folder', 'Please select a source folder.')
            return
        if not dst:
            messagebox.showerror('No Output Folder', 'Please select an output folder.')
            return
        if not Path(src).is_dir():
            messagebox.showerror('Invalid Source', f'Folder not found:\n{src}')
            return
        if Path(src).resolve() == Path(dst).resolve():
            messagebox.showerror('Same Folder', 'Source and output folders must be different.')
            return

        if self._check_dupes.get():
            self._reset_ui(enable_stop=False)
            self._run_dedup_scan(src, dst)
        else:
            self._reset_ui()
            self._start_processing(src, dst, frozenset())

    def _reset_ui(self, enable_stop: bool = True):
        self._log.configure(state='normal')
        self._log.delete('1.0', 'end')
        self._log.configure(state='disabled')
        self._progress['value'] = 0
        self._stat_total.set(0)
        self._stat_fixed.set(0)
        self._stat_nojson.set(0)
        self._stat_errors.set(0)
        self._prog_label.config(text='Starting…')
        self._prog_file.config(text='')
        self._btn_start.config(state='disabled')
        self._btn_stop.config(state='normal' if enable_stop else 'disabled')

    def _start_processing(self, src: str, dst: str, skip: frozenset):
        self._processor = Processor(
            src=src, dst=dst,
            copy_unmatched=self._copy_unmatched.get(),
            output_mode=self._output_mode.get(),
            on_log=self._log_msg,
            on_progress=self._update_progress,
            on_stats=self._update_stats,
            on_done=self._on_done,
            skip_files=skip,
        )
        self._thread = threading.Thread(target=self._processor.run, daemon=True)
        self._thread.start()

    def _run_dedup_scan(self, src: str, dst: str):
        self._progress.configure(mode='indeterminate')
        self._progress.start(15)
        self._prog_label.config(text='Scanning for duplicate files…')

        def _on_scan_progress(current: int, total: int):
            def _do():
                try:
                    self._progress.stop()
                except Exception:
                    pass
                self._progress.configure(mode='determinate')
                pct = int(current / total * 100) if total else 0
                self._progress['value'] = pct
                self._prog_label.config(
                    text=f'Scanning for duplicates: {current} / {total}  ({pct} %)'
                )
            self.after(0, _do)

        def _worker():
            src_path = Path(src)
            matches  = find_duplicates(src_path, src_path, _on_scan_progress)
            self.after(0, lambda: self._on_dedup_scan_done(matches, src, dst))

        self._thread = threading.Thread(target=_worker, daemon=True)
        self._thread.start()

    def _on_dedup_scan_done(self, matches, src: str, dst: str):
        try:
            self._progress.stop()
        except Exception:
            pass
        self._progress.configure(mode='determinate')
        self._progress['value'] = 0

        if not matches:
            self._log_msg('info', 'No duplicates found — proceeding with processing.')
            self._prog_label.config(text='No duplicates found.')
            self._reset_ui()
            self._start_processing(src, dst, frozenset())
            return

        win = DuplicateReviewWindow(self, matches, Path(src))
        if win.action is None:
            self._log_msg('warn', 'Duplicate review cancelled — processing aborted.')
            self._btn_start.config(state='normal')
            self._btn_stop.config(state='disabled')
            self._prog_label.config(text='Cancelled.')
            return

        skip = frozenset(m.file_b for m in matches) if win.action == 'skip_dupes' else frozenset()
        self._reset_ui()
        self._start_processing(src, dst, skip)

    def _on_stop(self):
        if self._processor:
            self._processor.stop()
        self._btn_stop.config(state='disabled')

    def _on_open_output(self):
        dst = self._dst_row.get()
        if dst and Path(dst).is_dir():
            if _SYS == 'Windows':
                os.startfile(dst)           # type: ignore[attr-defined]
            elif _SYS == 'Darwin':
                subprocess.Popen(['open', dst])
            else:
                subprocess.Popen(['xdg-open', dst])
        else:
            messagebox.showinfo('No Output Folder', 'Please select an output folder first.')


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == '__main__':
    app = App()
    app.mainloop()
