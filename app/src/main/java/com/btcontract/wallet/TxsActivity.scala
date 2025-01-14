package com.btcontract.wallet

import android.widget.RadioGroup.OnCheckedChangeListener
import android.widget.AbsListView.OnScrollListener
import collection.JavaConversions.asScalaBuffer
import org.bitcoinj.core.TransactionConfidence
import android.view.View.OnClickListener
import android.app.AlertDialog.Builder
import android.text.format.DateUtils
import scala.collection.mutable
import android.content.Intent
import scala.util.Success
import android.os.Bundle
import android.text.Html
import android.net.Uri
import java.util.Date

import android.view.{View, ViewGroup}
import Utils.{humanAddr, wrap, denom, Outputs, PayDatas, none, sumIn, sumOut, app}
import R.string.{txs_received_to, txs_sent_to, txs_many_received_to, txs_many_sent_to, err_general}
import R.string.{txs_yes_fee, txs_incoming, txs_noaddr, dialog_ok, no_funds}
import OnScrollListener.SCROLL_STATE_IDLE
import org.bitcoinj.core._
import android.widget._


class TxsActivity extends InfoActivity { me =>
  def onFail(exc: Throwable): Unit = new Builder(me).setMessage(err_general).show
  lazy private[this] val time = String.format("%1$tb %1$te&#x200b;,&#160;%1$tY&#x200b;,&#160;%1$tR", _: Date)
  lazy private[this] val head = getLayoutInflater.inflate(R.layout.frag_denom_and_count_head, null)
  lazy private[this] val allButton = getLayoutInflater.inflate(R.layout.frag_txs_all, null)
  lazy private[this] val txsNum = head.findViewById(R.id.txsNumber).asInstanceOf[TextView]
  lazy private[this] val list = findViewById(R.id.itemsList).asInstanceOf[ListView]

  // Confirmation rings, number of txs and implicits for denom
  lazy private[this] val confOpts = getResources getStringArray R.array.txs_normal_conf
  lazy private[this] val txsOpts = getResources getStringArray R.array.txs_total
  lazy private[this] implicit val dc = new DenomControl(me, head)
  lazy private[this] implicit val noFunds = getString(no_funds)

  // Sent/received templates and fee
  lazy private[this] val yesFee = me getString txs_yes_fee
  lazy private[this] val incoming = me getString txs_incoming
  lazy private[this] val addrUnknown = me getString txs_noaddr
  lazy private[this] val rcvdManyTo = me getString txs_many_received_to
  lazy private[this] val sentManyTo = me getString txs_many_sent_to
  lazy private[this] val rcvdTo = me getString txs_received_to
  lazy private[this] val sentTo = me getString txs_sent_to

  // Local state
  var cache = mutable.Map.empty[Sha256Hash, TxCache]
  var adapter: TxsListAdapter = null

  val taskListener = new WalletListener
  val txsListListener = new AbstractWalletEventListener {
    override def onReorganize(w: Wallet) = me runOnUiThread adapter.notifyDataSetChanged
    override def onTransactionConfidenceChanged(w: Wallet, tx: Transaction) = if (tx.getConfidence.getDepthInBlocks < 6) onReorganize(w)
    override def onCoinsReceived(w: Wallet, tx: Transaction, pb: Coin, nb: Coin) = if (nb isGreaterThan pb) me runOnUiThread say(tx)
    override def onCoinsSent(w: Wallet, tx: Transaction, pb: Coin, nb: Coin) = me runOnUiThread say(tx)

    def say(freshTransaction: Transaction) = {
      adapter.transactions.add(0, freshTransaction)
      txsNum setText app.plurOrZero(txsOpts, adapter.getCount)
      adapter.notifyDataSetChanged
    }
  }

  // Initialize this activity, method is run once
  override def onCreate(savedInstanceState: Bundle) =
  {
    super.onCreate(savedInstanceState)
    val linesNum = if (scrHeight < 4.5) 3 else if (scrHeight < 4.8) 4
      else if (scrHeight < 5.1) 5 else if (scrHeight < 5.5) 6
      else if (scrHeight < 6.2) 10 else 12

    if (app.isAlive) {
      add(constantListener.mkTxt, Informer.PEERS).ui.run
      new Anim(app.kit.currentBalance, Utils.appName)
      setContentView(R.layout.activity_txs)

      adapter = if (scrWidth < 2.0) new TxsListAdapter(new TxViewHolder(_), R.layout.frag_transaction_small)
        else if (scrWidth < 3.2) new TxsListAdapter(new TxViewHolderNormal(_), R.layout.frag_transaction_normal)
        else if (scrWidth < 4.4) new TxsListAdapter(new TxViewHolder(_), R.layout.frag_transaction_large)
        else new TxsListAdapter(new TxViewHolderNormal(_), R.layout.frag_transaction_extra)

      // Periodic list update
      list setOnScrollListener new ScrollListener {
        timer.schedule(me anyToRunnable go, 10000, 10000)
        def onScrollStateChanged(v: AbsListView, newState: Int) = state = newState
        def go = if (SCROLL_STATE_IDLE == state) adapter.notifyDataSetChanged
        var state = SCROLL_STATE_IDLE
      }

      // pos - 1 because header is present
      list setOnItemClickListener new AdapterView.OnItemClickListener {
        def onItemClick(par: AdapterView[_], v: View, pos: Int, id: Long) = {
          val detailsForm = getLayoutInflater.inflate(R.layout.frag_transaction_details, null)
          val listCon = getLayoutInflater.inflate(R.layout.frag_center_list, null).asInstanceOf[ListView]
          val outside = detailsForm.findViewById(R.id.viewTxOutside).asInstanceOf[TextView]
          val copy = detailsForm.findViewById(R.id.copyTxHash).asInstanceOf[Button]
          val transaction = adapter getItem pos - 1
          val hash = transaction.getHash
          val entry = cache(hash)

          // Take special pain to inform if transaction is dead
          val totalSum = transaction.getConfidence.getConfidenceType match {
            case TransactionConfidence.ConfidenceType.DEAD => app getString R.string.txs_dead
            case _ => s"${entry.transactAmount}<br><small>${me time transaction.getUpdateTime}</small>"
          }

          // Wire everything up
          def site = new Intent(Intent.ACTION_VIEW, Uri parse s"https://blockexplorer.com/tx/$hash")
          val txt = for (payment <- entry.pays) yield Html.fromHtml(payment pretty entry.sumDirection)
          outside setOnClickListener new OnClickListener { def onClick(v: View) = me startActivity site }
          copy setOnClickListener new OnClickListener { def onClick(v: View) = app setBuffer hash.toString }
          listCon setAdapter new ArrayAdapter(me, R.layout.frag_top_tip, R.id.actionTip, txt.toArray)
          mkForm(me negBld dialog_ok, Html fromHtml totalSum, listCon)
          listCon addHeaderView detailsForm
        }
      }

      // Setup selector
      dc.radios check dc.nowMode
      dc.radios setOnCheckedChangeListener new OnCheckedChangeListener {
        def onCheckedChanged(r: RadioGroup, n: Int) = wrap(adapter.notifyDataSetChanged)(dc.update)
      }

      // Wait for transactions list
      <(app.kit.wallet.getTransactionsByTime, onFail) { result =>
        txsNum setText app.plurOrZero(txsOpts, result.size)
        app.kit.wallet addEventListener txsListListener

        // Show lomited txs list
        val range = scala.math.min(linesNum, result.size)
        if (range < result.size) list addFooterView allButton
        adapter.transactions = result.subList(0, range)
        list.addHeaderView(head, null, true)
        list setAdapter adapter
      }

      // Wire up listeners
      app.kit.peerGroup addEventListener new CatchUpTracker
      app.kit.peerGroup addEventListener constantListener
      app.kit.wallet addEventListener taskListener
    } else me exitTo mainActivClass
  }

  override def onResume = wrap(super.onResume) {
    prefs.edit.putBoolean(AbstractKit.SACK_OR_TXS, false).commit
  }

  override def onDestroy = wrap(super.onDestroy) {
    app.kit.peerGroup removeEventListener constantListener
    app.kit.wallet removeEventListener txsListListener
    app.kit.wallet removeEventListener taskListener
  }

  def showAll(view: View) = {
    list removeFooterView allButton
    <(app.kit.wallet.getTransactionsByTime, onFail) { result =>
      wrap(adapter.notifyDataSetChanged)(adapter.transactions = result)
    }
  }

  // Adapter

  class TxsListAdapter(mk: View => TxViewHolder, id: Int) extends BaseAdapter {
    def getView(transactionPosition: Int, convertView: View, parent: ViewGroup) = {
      val view = if (null == convertView) getLayoutInflater.inflate(id, null) else convertView
      val hold = if (null == view.getTag) mk(view) else view.getTag.asInstanceOf[TxViewHolder]
      hold fillView getItem(transactionPosition)
      view
    }

    var transactions: java.util.List[Transaction] = null
    def getItem(pos: Int) = transactions get pos
    def getItemId(txnPos: Int) = txnPos
    def getCount = transactions.size
  }

  // Tx details converters

  def confMap(num: Int) = num match {
    case 0 => R.drawable.conf0 case 1 => R.drawable.conf1
    case 2 => R.drawable.conf2 case 3 => R.drawable.conf3
    case 4 => R.drawable.conf4 case _ => R.drawable.conf5
  }

  def when(date: Date, now: Long) = date.getTime match { case ago =>
    if (now - ago < 691200000) DateUtils.getRelativeTimeSpanString(ago, now, 0)
    else Html fromHtml time(date)
  }

  def feeString(entry: TxCache) = entry.fee match {
    case canNotDetermine if entry.value.isPositive => incoming
    case fee if null == fee || fee.isZero => yesFee format denom(Coin.ZERO)
    case fee => yesFee format denom(fee)
  }

  def summary(outs: PayDatas, incoming: Boolean) = outs match {
    case Seq(pay, _, _*) if incoming => rcvdManyTo.format(humanAddr(pay.adr), outs.size - 1)
    case Seq(pay, _, _*) => sentManyTo.format(humanAddr(pay.adr), outs.size - 1)
    case Seq(pay) if incoming => rcvdTo format humanAddr(pay.adr)
    case Seq(pay) => sentTo format humanAddr(pay.adr)
    case _ if incoming => rcvdTo format addrUnknown
    case _ => sentTo format addrUnknown
  }

  def getOuts(outs: Outputs, acc: PayDatas, way: Boolean) = {
    for (out <- outs if out.isMine(app.kit.wallet) == way) try {
      acc += PayData(app.kit toAdr out, Success apply out.getValue)
    } catch none
    acc
  }

  def makeCache(txn: Transaction) = txn getValue app.kit.wallet match { case sum =>
    TxCache(getOuts(txn.getOutputs, mutable.Buffer.empty, sum.isPositive), txn.getFee, sum)
  }

  // Transaction cache item and view holders

  case class TxCache(pays: PayDatas, fee: Coin, value: Coin) {
    val htmlSummary = Html fromHtml summary(pays, value.isPositive)
    val sumDirection = if (value.isPositive) sumIn else sumOut
    def transactAmount = sumDirection format denom(value)
  }

  class TxViewHolder(view: View) {
    val transactCircle = view.findViewById(R.id.transactCircle).asInstanceOf[ImageView]
    val transactWhen = view.findViewById(R.id.transactWhen).asInstanceOf[TextView]
    val transactSum = view.findViewById(R.id.transactSum).asInstanceOf[TextView]
    val feeAmount = view.findViewById(R.id.feeAmount).asInstanceOf[TextView]
    val address = view.findViewById(R.id.address).asInstanceOf[TextView]
    view setTag this

    def status(tc: TransactionConfidence) = tc.getConfidenceType match {
      case TransactionConfidence.ConfidenceType.DEAD => transactCircle setImageResource R.drawable.dead
      case anyOtherConfidenceType => transactCircle setImageResource confMap(tc.getDepthInBlocks)
    }

    def fillView(txn: Transaction) = {
      val entry = cache.getOrElseUpdate(txn.getHash, me makeCache txn)
      transactWhen setText when(txn.getUpdateTime, System.currentTimeMillis)
      transactSum setText Html.fromHtml(entry.transactAmount)
      feeAmount setText feeString(entry)
      address setText entry.htmlSummary
      status(txn.getConfidence)
    }
  }

  class TxViewHolderNormal(view: View) extends TxViewHolder(view) {
    val status = view.findViewById(R.id.transactStatus).asInstanceOf[TextView]

    override def status(tc: TransactionConfidence) = {
      val confirmationStatus = tc.getConfidenceType match {
        case TransactionConfidence.ConfidenceType.DEAD => me getString R.string.txs_normal_dead
        case _ => if (tc.getDepthInBlocks > 999) "999+" else app.plurOrZero(confOpts, tc.getDepthInBlocks)
      }

      // Text status and then also a circle
      status setText confirmationStatus
      super.status(tc)
    }
  }
}