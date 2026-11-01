package tw.nekomimi.nekogram.folder;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Локальные папки: живут только в этом клиенте, на сервер не уходят.
 * Нужны, чтобы обойти серверный лимит папок (10 / 20 с премиумом).
 * Отличаются от серверных только id (>= LOCAL_ID_START).
 */
public final class LocalFolders {

    public static final int LOCAL_ID_START = 1_000_000;

    private LocalFolders() {
    }

    public static boolean isLocal(int filterId) {
        return filterId >= LOCAL_ID_START;
    }

    public static boolean isLocal(MessagesController.DialogFilter filter) {
        return filter != null && isLocal(filter.id);
    }

    /** Сколько папок реально лежит на сервере (без «Все чаты» и без локальных). */
    public static int serverFolderCount(MessagesController mc) {
        int n = 0;
        ArrayList<MessagesController.DialogFilter> filters = mc.getDialogFilters();
        for (int i = 0; i < filters.size(); i++) {
            MessagesController.DialogFilter f = filters.get(i);
            if (f != null && !f.isDefault() && !isLocal(f.id)) n++;
        }
        return n;
    }

    /** Лимит сервера по настоящему премиуму (без «локального премиума» Nagram). */
    public static int serverFolderLimit(MessagesController mc, int account) {
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        boolean realPremium = me != null && me.premium;
        return realPremium ? mc.dialogFiltersLimitPremium : mc.dialogFiltersLimitDefault;
    }

    /** id для новой папки: серверный, пока есть место на сервере, иначе локальный. */
    public static int newFilterId(MessagesController mc, int account) {
        boolean local = serverFolderCount(mc) >= serverFolderLimit(mc, account);
        int id = local ? LOCAL_ID_START : 2;
        while (mc.dialogFiltersById.get(id) != null) {
            id++;
        }
        return id;
    }

    /**
     * Перехват запросов к серверу в ConnectionsManager.
     * true — запрос обработан тут и на сервер не уходит.
     */
    public static boolean intercept(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        try {
            if (object instanceof TLRPC.TL_messages_updateDialogFilter) {
                TLRPC.TL_messages_updateDialogFilter req = (TLRPC.TL_messages_updateDialogFilter) object;
                if (isLocal(req.id)) {
                    TLRPC.TL_boolTrue ok = new TLRPC.TL_boolTrue();
                    if (onComplete != null) onComplete.run(ok, null);
                    if (onCompleteTimestamp != null) onCompleteTimestamp.run(ok, null, 0);
                    return true;
                }
            } else if (object instanceof TLRPC.TL_messages_updateDialogFiltersOrder) {
                // порядок локальных папок серверу не отправляем — он про них не знает
                TLRPC.TL_messages_updateDialogFiltersOrder req = (TLRPC.TL_messages_updateDialogFiltersOrder) object;
                for (int i = req.order.size() - 1; i >= 0; i--) {
                    Integer id = req.order.get(i);
                    if (id != null && isLocal(id)) req.order.remove(i);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }
}
