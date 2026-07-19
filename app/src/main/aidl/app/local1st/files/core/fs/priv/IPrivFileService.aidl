package app.local1st.files.core.fs.priv;

import android.os.ParcelFileDescriptor;

interface IPrivFileService {
    // Shizuku reserves this transaction so it can terminate a user service explicitly.
    void destroy() = 16777114;

    ParcelFileDescriptor open(String path, int mode) = 1;

    String exec(String script) = 2;
}
