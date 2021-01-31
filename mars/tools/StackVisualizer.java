/*
 * MIT License
 *
 * Copyright (c) 2018-2021 George Z. Zachos and Petros Manousis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mars.tools;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Observable;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import mars.Globals;
import mars.ProgramStatement;
import mars.assembler.Symbol;
import mars.assembler.SymbolTable;
import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;
import mars.mips.hardware.Register;
import mars.mips.hardware.RegisterAccessNotice;
import mars.mips.hardware.RegisterFile;
import mars.mips.instructions.Instruction;
import mars.simulator.BackStepper;
import mars.simulator.Simulator;
import mars.simulator.SimulatorNotice;
import mars.util.Binary;
import mars.venus.FileStatus;
import mars.venus.VenusUI;

/**
 * Allows the user to view in real time the $sp-relative memory modification operations taking place in the stack segment.
 * The user can also observe how the stack grows. The address pointed by the stack pointer is displayed in an orange background
 * while the whole word-length data in a yellow one. Lower addresses have a grey background (given that stack growth takes
 * place form higher to lower addresses). The names of the registers whose contents are stored (sw, sh, sb etc.) in the stack,
 * are shown in the "Stored Reg" column. In the "Call Layout" column, the subroutine frame (activation record) layout is displayed,
 * with subroutine names placed on the highest address of the corresponding frames.
 * 
 * GitHub repository: <a href="https://github.com/gzachos/mars-stack-visualizer">https://github.com/gzachos/mars-stack-visualizer</a>
 *
 * @author George Z. Zachos <gzachos@cse.uoi.gr>
 * @author Petros Manousis <pmanousi@cs.uoi.gr>
 */
@SuppressWarnings({ "serial", "deprecation" })
public class StackVisualizer extends AbstractMarsToolAndApplication {
	private static String name        = "Stack Visualizer";
	private static String versionID   = "1.0";
	private static String version     = "Version " + versionID + " (George Z. Zachos, Petros Manousis)";
	private static String heading     = "Visualizing Stack Modification Operations";
	private static String releaseDate = "30-Sep-2019";

	// We need the following definition here to initialize numberOfColumns
	/** Table column names for displaying data per byte. */
	private final String[] colNamesWhenDataPerByte = {"Address", "+3", "+2", "+1", "+0", "Stored Reg", "Call Layout"};
	/** Table column names for displaying data per word. */
	private final String[] colNamesWhenNotDataPerByte = {"Address", "Word-length Data", "Stored Reg", "Call Layout"};

	/**
	 * True if {@link StackVisualizer} is currently running
	 * as a stand-alone program (MARS application)
	 */
	private static boolean inStandAloneMode = false;

	/*
	 * Memory.stackBaseAddress:  word-aligned
	 * Memory.stackLimitAddress: word-aligned
	 * Max stack address value:  Memory.stackBaseAddress + (WORD_LENGTH_BYTES-1)
	 * Min stack address value:  Memory.stackLimitAddress
	 *
	 * Stack grows towards lower addresses: .stackBaseAddress > .stackLimitAddress
	 * Word-length operations can take place in both .stackBaseAddress and .stackLimitAddress
	 */
	/** Register number of stack pointer (29) */
	private final int     SP_REG_NUMBER             = RegisterFile.STACK_POINTER_REGISTER;
	/** Stack pointer's initial address/value */
	private final int     SP_INIT_ADDR              = Memory.stackPointer;
	private final Memory  memInstance               = Memory.getInstance();
	private final boolean endianness                = memInstance.getByteOrder();
	private final boolean LITTLE_ENDIAN             = Memory.LITTLE_ENDIAN;       // for quick access
	/** MIPS word length in bytes. */
	private final int     WORD_LENGTH_BYTES         = Memory.WORD_LENGTH_BYTES;   // for quick access
	/** MIPS word length in bits. */
	private final int     WORD_LENGTH_BITS          = WORD_LENGTH_BYTES << 3;
	/** I-format RS (source register) index in operand list. */
	private final int     I_RS_OPERAND_LIST_INDEX   = 0;
	/** J-format Address index in operand list. */
	private final int     J_ADDR_OPERAND_LIST_INDEX = 0;
	/** R-format RS (source register) index in operand list. */
	private final int     R_RS_OPERAND_LIST_INDEX   = 0;
	/** Initial maximum value stack pointer can get: {@code SP_INIT_ADDR + (WORD_LENGTH_BYTES-1)}. */
	private final int     INITIAL_MAX_SP_VALUE      = SP_INIT_ADDR + (WORD_LENGTH_BYTES - 1); // Max byte address
	/** Maximum value stack pointer can currently take (not word-aligned). */
	private int           maxSpValue                = INITIAL_MAX_SP_VALUE;
	/** Maximum value stack pointer can currently take (word-aligned). */
	private int           maxSpValueWordAligned     = SP_INIT_ADDR;
	/** Register name to be stored in stack segment. */
	private String        regNameToBeStoredInStack  = null;
	/** Name of the (subroutine) frame to be allocated in stack segment. */
	private String        frameNameToBeCreated      = null;
	/**
	 * Return Address Stack. Target addresses of jal instructions are pushed and
	 * then are popped and matched when jr instructions are encountered.
	 */
	private final ArrayList<Integer> ras            = new ArrayList<Integer>();
	/**
	 * Active subroutine statistics. Stores how many times each subroutine is active
	 * (called but is yet to complete execution).
	 */
	private final ActiveSubroutineStats activeFunctionCallStats = new ActiveSubroutineStats();

	// GUI-Related fields
	private final int     windowWidth               = 600;
	private final int     windowHeight              = 600;
	/** Table column index where memory address should be stored. Should always be first column. */
	private final int     ADDRESS_COLUMN            = 0;
	/** Table column index where the first byte of memory data should be stored. Should always be second column. */
	private final int     FIRST_BYTE_COLUMN         = 1;
	/** Table column index where the last byte of memory data should be stored. */
	private final int     LAST_BYTE_COLUMN          = FIRST_BYTE_COLUMN + (WORD_LENGTH_BYTES - 1);
	/** Table column index where the word-length memory data should be stored. Should always be second column. */
	private final int     WORD_COLUMN               = 1;
	/** How many rows the table should initially have. */
	private final int     INITIAL_ROW_COUNT         = 30;
	/** Current number of table columns. */
	private int           numberOfColumns           = colNamesWhenDataPerByte.length;
	/** Current number of table rows. (-1) before table initialization. */
	private int           numberOfRows              = -1;
	/** Offset of frame name ("Call Layout") column from table end. */
	private final int     frameNameColOffsetFromEnd = 0;
	/** Offset of stored register column from table end. */
	private final int     storedRegColOffsetFromEnd = 1;
	/** Table column index where frame name should be stored. */
	private int           frameNameColumn           = calcTableColIndex(frameNameColOffsetFromEnd);
	/** Table column index where stored register name should be stored. */
	private int           storedRegisterColumn      = calcTableColIndex(storedRegColOffsetFromEnd);
	/** Threshold to decide whether more table rows should be added. */
	private final int     REMAINING_ROWS_THRESHOLD  = 5;
	/** Table row where stack pointer points to. */
	private int           spDataRowIndex            = 0;
	/** Table column where stack pointer points to. */
	private int           spDataColumnIndex         = LAST_BYTE_COLUMN;
	private JTable        table;
	private JPanel        panel;
	private JScrollPane   scrollPane;
	private JCheckBox     dataPerByte;
	private JCheckBox     hexAddressesCheckBox;
	private JCheckBox     hexValuesCheckBox;
	private final int     LIGHT_YELLOW = 0xFFFF99;
	private final int     LIGHT_ORANGE = 0xFFC266;
	private final int     LIGHT_GRAY   = 0xE0E0E0;
	private final int     GRAY         = 0x999999;
	private final int     WHITE        = 0xFFFFFF;
	private boolean       disabledBackStep = false;
	private final DefaultTableModel tableModel = new DefaultTableModel();

	/** Used for debugging purposes. */
	private final boolean debug = false, printMemContents = false, debugBackStepper = false;


	protected StackVisualizer(String title, String heading) {
		super(title, heading);
	}


	public StackVisualizer() {
		super(StackVisualizer.name + ", " + StackVisualizer.version, StackVisualizer.heading);
	}


	/**
	 * Main method provided for use as a MARS application (stand-alone program).
	 */
	public static void main(String[] args) {
		inStandAloneMode = true;
		new StackVisualizer(StackVisualizer.name + " stand-alone, " + StackVisualizer.version, StackVisualizer.heading).go();
	}


	@Override
	public String getName() {
		return name;
	}


	@Override
	protected JComponent buildMainDisplayArea() {
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.gridx = c.gridy = 0;
		c.weightx = c.weighty = 1.0;
		panel = new JPanel(new GridBagLayout());
		panel.setPreferredSize(new Dimension(windowWidth, windowHeight));
		for (String s : colNamesWhenDataPerByte)
			tableModel.addColumn(s);
		table = new JTable(tableModel);
		table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		table.getTableHeader().setReorderingAllowed(false);
		table.setEnabled(false);
		table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
				final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
				c.setBackground(calcTableCellColor(row, column));

				if ((dataPerByte.isSelected() && column >= FIRST_BYTE_COLUMN && column <= LAST_BYTE_COLUMN) ||
						(!dataPerByte.isSelected() && column == WORD_COLUMN))
					setHorizontalAlignment(SwingConstants.RIGHT);
				else if (column == storedRegisterColumn || column == frameNameColumn)
					setHorizontalAlignment(SwingConstants.CENTER);
				else
					setHorizontalAlignment(SwingConstants.LEFT);
				return c;
			}
		});
		// table.setAutoResizeMode( JTable.AUTO_RESIZE_OFF );
		resizeTableColumns(true);

		scrollPane = new JScrollPane(table);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrollPane.setVisible(true);
		panel.add(scrollPane, c);
		table.setFillsViewportHeight(true);

		c.gridy++;	// change line
		c.weightx = 1.0;
		c.weighty = 0;
		dataPerByte = new JCheckBox("Display data per byte");
		dataPerByte.setSelected(true);
		panel.add(dataPerByte, c);
		dataPerByte.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if(dataPerByte.isSelected() == false)
					transformTableModel(colNamesWhenNotDataPerByte);
				else
					transformTableModel(colNamesWhenDataPerByte);
			}
		});

		c.gridy++;	// change line
		hexAddressesCheckBox = new JCheckBox("Hexadecimal Addresses");
		hexAddressesCheckBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				getStackData();
				table.repaint();
			}
		});
		hexAddressesCheckBox.setSelected(true);
		panel.add(hexAddressesCheckBox, c);

		c.gridy++;	// change line
		hexValuesCheckBox = new JCheckBox("Hexadecimal Values");
		hexValuesCheckBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				getStackData();
				table.repaint();
			}
		});
		hexValuesCheckBox.setSelected(true);
		panel.add(hexValuesCheckBox, c);
		return panel;
	}


	/**
	 * Calculates what color table cell ({@code row},{@code column}) should be colored.
	 *
	 * @param row table cell row.
	 * @param column table cell column.
	 * @return the calculated color.
	 */
	private Color calcTableCellColor(int row, int column) {
		int color = WHITE;
		if (row == spDataRowIndex) {
			color = LIGHT_YELLOW;
			// $sp cell coloring doesn't work with user column reordering
			if (dataPerByte.isSelected() && column == spDataColumnIndex)
				color = LIGHT_ORANGE;
		}
		else if (row > spDataRowIndex) {
			color = GRAY;
		}
		else if (row % 2 == 0) {
			color = LIGHT_GRAY;
		}
		return new Color(color);
	}


	private void resizeTableColumns(boolean dataPerByte) {
		TableColumnModel columnModel = table.getColumnModel();
		for (int colIndex = 0 ; colIndex < columnModel.getColumnCount(); colIndex++) {
			TableColumn col = columnModel.getColumn(colIndex);
			int min, pref;
			min = pref = 75;
			if (dataPerByte) {
				if (colIndex >= FIRST_BYTE_COLUMN && colIndex <= LAST_BYTE_COLUMN) {
					min = 25;
					pref = 50;
				} else if (colIndex == ADDRESS_COLUMN) {
					min = 75;
					pref = 100;
				} else if (colIndex == storedRegisterColumn) {
					min = 25;
					pref = 75;
				} else if (colIndex == frameNameColumn) {
					min = 25;
					pref = 150;
				}
			} else {
				if (colIndex == frameNameColumn) {
					min = 25;
					pref = 150;
				}
			}
			col.setMinWidth(min);
			col.setPreferredWidth(pref);
		}
	}


	/**
	 * Transform table model so that new columns match {@code columnNames[]}.
	 *
	 * @param columnNames the new table columns.
	 */
	private void transformTableModel(String columnNames[]) {
		Object storedRegColumnData[] = new Object[numberOfRows];
		Object frameNameColumnData[] = new Object[numberOfRows];

		// Backup storedRegister and frameName columns
		for (int row = 0; row < numberOfRows; row++) {
			storedRegColumnData[row] = tableModel.getValueAt(row, storedRegisterColumn);
			frameNameColumnData[row] = tableModel.getValueAt(row, frameNameColumn);
		}

		table.setVisible(false);   // Used to avoid rendering delays
		tableModel.setColumnCount(0);	// Clear table columns
		for (String s : columnNames)
			tableModel.addColumn(s);	// Add new table columns

		// Update table-related data
		numberOfColumns = tableModel.getColumnCount();
		numberOfRows = tableModel.getRowCount();
		frameNameColumn = calcTableColIndex(frameNameColOffsetFromEnd);
		storedRegisterColumn = calcTableColIndex(storedRegColOffsetFromEnd);
		resizeTableColumns(dataPerByte.isSelected());

		// Restore toredRegister and frameName columns
		for (int row = 0; row < numberOfRows; row++) {
			tableModel.setValueAt(storedRegColumnData[row], row, storedRegisterColumn);
			tableModel.setValueAt(frameNameColumnData[row], row, frameNameColumn);
		}
		getStackData();
		table.repaint();
		table.setVisible(true);
	}


	@Override
	protected void initializePreGUI() {
		if (inStandAloneMode == true)
			return;

		Simulator sim = Simulator.getInstance();
		sim.addObserver(this);
	}


	@Override
	protected void initializePostGUI() {
		if (inStandAloneMode == true) {
			getStackData();
			updateSpDataRowColIndex();
		} else {
			connectButton.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					if (connectButton.isConnected()) {
						restoreBackStepper();
					} else {
						/*
						 * User program should be recompiled (and executed) after
						 * StackVisualizer is launched. This is required for
						 * coherently storing the subroutine call stack.
						 */
						runButtonsSetEnabled(false);
						/* Connecting StackVisualizer in the middle of program execution
						 * will disable Back Stepper but the button will show as enabled.
						 * Maybe we should disable it by hand or just don't mess with it.
						 */
						disableBackStepper();

						getStackData();
						updateSpDataRowColIndex();
						table.repaint();

						String msg = "Back Stepping has been disabled.\n"
								+ "Already running programs should be assembled again.";
						showMessageWindow(msg);
					}
				}
			});
		}
		addNewTableRows(INITIAL_ROW_COUNT);
		updateSpDataRowColIndex();
		table.repaint(); // Maybe we can remove this
	}


	/**
	 * Fills table with data directly from Mars' memory instance.
	 *
	 * This method fires a {@link MemoryAccessNotice} every time it reads from memory.
	 * For this reason it should not be called in a code block handling a
	 * {@link MemoryAccessNotice} of {@code AccessNotice.READ} type as it will lead
	 * in infinite recursive calls of itself.
	 */
	private void getStackData() {
		int col;

		if (printMemContents)
			System.out.println("getStackData start");
		/*
		 * MARS supports three memory configurations. Only in the default configuration
		 * the initial stack pointer value does NOT point to the highest address of the
		 * stack. Nevertheless, in all three configurations, the initial address pointed
		 * by $sp is a valid address whose contents can be written.
		 *
		 * TODO support visualization of addresses higher than initial $sp value
		 *
		 * Initial value of spAddr is 0x7FFFEFFC = 2147479548 (Default MIPS memory configuration).
		 * The first 4 bytes (1 word) to be displayed are:
		 * 0x7FFFEFFF, 0x7FFFEFFE, 0x7FFFEFFD, 0x7FFFEFFC or in decimal value:
		 * 2147479551, 2147479550, 2147479549, 2147479548.
		 */
		for (int row = 0, addr = maxSpValueWordAligned; row < numberOfRows; row++, addr -= WORD_LENGTH_BYTES) {
			tableModel.setValueAt(formatAddress(addr), row, ADDRESS_COLUMN);
			try {
				if (dataPerByte.isSelected()) {
					/* Access word one byte at a time */
					for (int bi = 0; bi < WORD_LENGTH_BYTES; bi++) {
						int byteValue;
						/*
						 * Endianness determines whether byte position in value and
						 * byte position in memory match.
						 */
						col = (endianness == LITTLE_ENDIAN) ? LAST_BYTE_COLUMN - bi : FIRST_BYTE_COLUMN + bi;
						/*
						 * MARS checks for addresses out of range using word-aligned addresses.
						 * This means that the word on Memory.stackBaseAddress (highest stack address) can
						 * be accessed during word-length data operations but not during half-word or byte-length
						 * operations. This behavior is asymmetrical among the three memory configurations
						 * supported by MARS.
						 */
						if (addr >= Memory.stackBaseAddress && addr <= (Memory.stackBaseAddress + (WORD_LENGTH_BYTES-1))) {
							/* In case of highest stack address, access whole word and then 
							 * use shift and bitwise operations to get each byte.
							 */
							int word = memInstance.getWordNoNotify(alignToCurrentWordBoundary(addr));
							byteValue = (word >> (bi << 3)) & 0xff;
						} else {
							byteValue = memInstance.getByte(addr + bi);
						}
						tableModel.setValueAt(formatNByteLengthMemContents(1, byteValue), row, col);
					}
				} else {
					tableModel.setValueAt(formatNByteLengthMemContents(WORD_LENGTH_BYTES, memInstance.getWord(addr)),
							row, WORD_COLUMN);
				}
				if (printMemContents) {
					System.out.print(tableModel.getValueAt(row, 0) + ": ");
					if (dataPerByte.isSelected()) {
						for (int i = FIRST_BYTE_COLUMN; i <= LAST_BYTE_COLUMN; i++)
							System.out.print(tableModel.getValueAt(row, i) + (i == LAST_BYTE_COLUMN ? "" : ","));
					} else {
						System.out.print(tableModel.getValueAt(row, WORD_COLUMN));
					}
					System.out.print(" (" + tableModel.getValueAt(row, storedRegisterColumn) + ")");
					System.out.println(" [" + tableModel.getValueAt(row, frameNameColumn) + "]");
				}
			} catch (AddressErrorException aee) {
				System.err.println("getStackData(): " + formatAddress(aee.getAddress()) + " AddressErrorException");
			}
		}

		if (printMemContents)
			System.out.println("getStackData end\n");
	}


	@Override
	protected void addAsObserver() {
		// To observe stack segment, actual parameters should be
		// reversed due to higher to lower address stack growth.
		addAsObserver(Memory.stackLimitAddress, Memory.stackBaseAddress);
		/*
		 * TODO: verify that Memory.stackBaseAdress+1 to Memory.stackBaseAdress+3
		 * can be observed during half word- or byte-length operations.
		 * Update check in getTableRowIndex() too if needed.
		 */
		addAsObserver(RegisterFile.getRegisters()[SP_REG_NUMBER]);
		addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
	}


	@Override
	protected void deleteAsObserver() {
		super.deleteAsObserver(); // Stop observing memory (default)
		deleteAsObserver(RegisterFile.getRegisters()[SP_REG_NUMBER]); // Stop observing $sp
	}


	@Override
	protected void processMIPSUpdate(Observable resource, AccessNotice notice) {
		// System.out.println(notice.accessIsFromMIPS() +" " + notice.accessIsFromGUI() + " " + notice);

		if (!notice.accessIsFromMIPS())
			return;

		if (notice instanceof MemoryAccessNotice) {
			MemoryAccessNotice m = (MemoryAccessNotice) notice;
			if (Memory.inTextSegment(m.getAddress()))
				processTextMemoryUpdate(m);
			else
				processStackMemoryUpdate(m);
		}
		else if (notice instanceof RegisterAccessNotice) {
			RegisterAccessNotice r = (RegisterAccessNotice) notice;
			processRegisterAccessNotice(r);
		}
	}


	private void processRegisterAccessNotice(RegisterAccessNotice notice) {
		// Currently only $sp is observed
		// TODO: What about observing frame pointer?
		if (notice.getAccessType() == AccessNotice.READ)
			return;

		if (debug)
			System.out.println("\nRegisterAccessNotice (W): " + notice.getRegisterName() + " value: " + getSpValue());

		if (notice.getRegisterName().equals("$sp")) {
			int oldSpDataRowIndex = spDataRowIndex;
			updateSpDataRowColIndex();
			resetStoredRegAndFrameNameColumns(spDataRowIndex + 1, oldSpDataRowIndex);
//			 System.out.println("SP value: " + formatAddress(getSpValue()) + " - tableIndex: " + spDataRowIndex);
			// Add more rows if we are reaching current row count
			if (spDataRowIndex + REMAINING_ROWS_THRESHOLD > numberOfRows) {
				addNewTableRows(5);
			}
			table.repaint(); // Required for coloring $sp position during popping.
		}
	}


	private void processStackMemoryUpdate(MemoryAccessNotice notice) {
		String regName = "", frameName = "";

		if (notice.getAccessType() == AccessNotice.READ)
			return;

		if (regNameToBeStoredInStack != null) {
			regName = regNameToBeStoredInStack;
			regNameToBeStoredInStack = null;
		}

		if (frameNameToBeCreated != null) {
			frameName = frameNameToBeCreated;
			frameNameToBeCreated = null;
		}

		if (debug) {
			System.out.println("\nStackAccessNotice (" +
					((notice.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
					+ notice.getAddress() + " value: " + notice.getValue() +
					" (stored: " + regName + ")");
		}

		int row;
		try {
			row = getTableRowIndex(notice.getAddress());
		} catch (SVException sve) {
			System.err.println("processStackMemoryUpdate(): " + sve.getMessage());
			return;
		}

		if (debug)
			System.out.println("Addr: " + formatAddress(notice.getAddress()) + " - tableIndex: " + row + " (" + regName + ")");

		tableModel.setValueAt(regName, row, storedRegisterColumn);
		tableModel.setValueAt(frameName, row, frameNameColumn);
		getStackData();
		table.repaint();
	}


	/**
	 * Adds more rows in table.
	 *
	 * @param rowNumber the number of rows to add.
	 */
	private void addNewTableRows(int rowNumber) {
		for (int ri = 0; ri < rowNumber; ri++)
			tableModel.addRow(new Object[numberOfColumns]);
		numberOfRows = tableModel.getRowCount();
		getStackData();
	}


	/**
	 * @return the index of the table row that {@code memAddress} should be stored
	 * if it belongs to the stack segment; else (-1).
	 * @throws SVException 
	 */
	private int getTableRowIndex(int memAddress) throws SVException {
		if (!isStackSegAddress(memAddress)) {
			throw new SVException("An address not in the stack segment was provided");
		}
		int rowIndex = (maxSpValueWordAligned - alignToCurrentWordBoundary(memAddress)) / WORD_LENGTH_BYTES;
		if (rowIndex >= numberOfRows) {
			addNewTableRows(rowIndex - numberOfRows + 10);
			table.repaint();
		}
		if (rowIndex < 0) { // Higher address than $sp value at program start
			throw new SVException("Addresses higher than " + formatAddress(maxSpValueWordAligned) + " are not currently supported");
		}
		return rowIndex;
	}


	/**
	 * @return the index of the table column that {@code memAddress} should be stored
	 * if it belongs to the stack segment; else (-1).
	 * @throws SVException 
	 */
	private int getTableColumnIndex(int memAddress) throws SVException {
		if (!isStackSegAddress(memAddress))
			throw new SVException("An address not in the stack segment was provided");
		return LAST_BYTE_COLUMN - (memAddress % WORD_LENGTH_BYTES);
	}


	/**
	 * @return true if {@code memAddress} is in stack segment; else false.
	 */
	private boolean isStackSegAddress(int memAddress) {
		return (memAddress >= Memory.stackLimitAddress && memAddress <= Memory.stackBaseAddress);
	}


	private void processTextMemoryUpdate(MemoryAccessNotice notice) {
		if (notice.getAccessType() == AccessNotice.WRITE)
			return;

		if (debug) {
			System.out.println("\nTextAccessNotice (R): " + notice.getAddress()
					+ " value: " + notice.getValue() /*+ " = "*/);
		}
//		printBin(notice.getValue());

		try {
			ProgramStatement stmnt =  memInstance.getStatementNoNotify(notice.getAddress());

			/*
			 * The check below is required in case user program is finished running
			 * by dropping of the bottom. This happens when an execution termination
			 * service (Code 10 in $v0) does NOT take place.
			 */
			if (stmnt == null)
				return;

			Instruction instr = stmnt.getInstruction();
			String instrName = instr.getName();
			int[] operands;

			if (isStoreInstruction(instrName)) {
				if (debug)
					System.out.println("Statement TBE: " + stmnt.getPrintableBasicAssemblyStatement());
				operands = stmnt.getOperands();
//				for (int i = 0; i < operands.length; i++)
//					System.out.print(operands[i] + " ");
//				System.out.println();
				regNameToBeStoredInStack = RegisterFile.getRegisters()[operands[I_RS_OPERAND_LIST_INDEX]].getName();
			}
			else if (isJumpInstruction(instrName) || isJumpAndLinkInstruction(instrName)) {
				int targetAdrress = stmnt.getOperand(J_ADDR_OPERAND_LIST_INDEX) * WORD_LENGTH_BYTES;
				String targetLabel = addrToTextSymbol(targetAdrress);
				if (isJumpAndLinkInstruction(instrName)) {
					registerNewSubroutineCall(stmnt, targetLabel);
				}
				if (targetLabel != null) {
					if (debug) {
						System.out.print("Jumping to: " + targetLabel);
						if (isJumpAndLinkInstruction(instrName))
							System.out.println(" (" + (ras.size()) + ")");
						else
							System.out.println("");
					}
				}
			}
			else if (isJumpRegInstruction(instrName)) {
				int targetRegister = stmnt.getOperand(R_RS_OPERAND_LIST_INDEX);
				Register reg = RegisterFile.getRegisters()[targetRegister];
				int returnAddress = reg.getValue();
				// returnAddress-4 is needed as PC+4 is stored in $ra when jal is executed.
				int jalStatementAddress = returnAddress - WORD_LENGTH_BYTES;
				ProgramStatement jalStatement =  memInstance.getStatementNoNotify(jalStatementAddress);
				int jalTargetAddress = jalStatement.getOperand(J_ADDR_OPERAND_LIST_INDEX) * WORD_LENGTH_BYTES;
				String exitingSubroutineName = addrToTextSymbol(jalTargetAddress);
				activeFunctionCallStats.removeCall(exitingSubroutineName);
				if (debug) {
					System.out.println("Returning from: " + exitingSubroutineName + " (" + ras.size() +
							") to line: " + jalStatement.getSourceLine());
				}

				try {
					Integer rasTopAddress = ras.remove(ras.size()-1);
					if (rasTopAddress.compareTo(jalStatementAddress) != 0) {
						System.err.println("Mismatching return address: " + formatAddress(rasTopAddress) + " vs " + formatAddress(jalStatementAddress) +
								" (Expected/jal vs Actual/jr)");
					}
				} catch (IndexOutOfBoundsException iobe) {
					/* Exception is thrown whenever:
					 * 1) Subroutine calling instructions are back-stepped (undone) and again executed.
					 * Undoing the last step should not be supported! FIXED: BackStepper is disabled.
					 *
					 * 2) In case StackVisualizer gets disconnected while user program is executing and
					 * then is again connected. FIXED: Tool's disconnect button is disabled during
					 * execution/simulation and then again enabled at the end.
					 *
					 * 3) When tool's Reset button is pressed while the user program is executing.
					 * FIXED: Removed ras and activeFunctionCallStats reset operations from reset()
					 */
					System.err.println("Mismatching number of subroutine calls and returns.");
				}
			}
		} catch (AddressErrorException aee) {
			System.err.println("processTextMemoryUpdate(): " + formatAddress(aee.getAddress()) + " AddressErrorException");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}


	/**
	 * Update {@code ras}, {@code activeFunctionCallStats} and {@code frameNameToBeCreated}
	 * as of a new subroutine call.
	 * @param stmnt the jump/jal instruction statement that invokes the new subroutine call.
	 * @param targetLabel the name/label of the new subroutine that is called.
	 */
	private void registerNewSubroutineCall(ProgramStatement stmnt, String targetLabel) {
		ras.add(stmnt.getAddress());
		Integer count = activeFunctionCallStats.addCall(targetLabel);
		frameNameToBeCreated = targetLabel + " (" + count + ")";
	}


	/**
	 * @param instrName instruction name.
	 *
	 * @return true if instruction name matches "sw", "sh", "sc" or "sb"; else false.
	 */
	private boolean isStoreInstruction(String instrName) {
		if (instrName.equals("sw") || instrName.equals("sh") ||
				instrName.equals("sc") || instrName.equals("sb"))
			return true;
		return false;
	}


	/**
	 * @param instrName instruction name.
	 *
	 * @return true if instruction name matches "j"; else false.
	 */
	private boolean isJumpInstruction(String instrName) {
		return (instrName.equals("j"));
	}


	/**
	 * @param instrName instruction name.
	 *
	 * @return true if instruction name matches "jal"; else false.
	 */
	private boolean isJumpAndLinkInstruction(String instrName) {
		return (instrName.equals("jal"));
	}


	/**
	 * @param instrName instruction name.
	 *
	 * @return true if instruction name matches "jr"; else false.
	 */
	private boolean isJumpRegInstruction(String instrName) {
		return (instrName.equals("jr"));
	}


	/**
	 * Translates a text segment address ({@code memAddress}) to a symbol/label.
	 *
	 * @return the corresponding label; else null.
	 */
	private String addrToTextSymbol(int memAddress) {
		String addrStr = String.valueOf(memAddress);
		SymbolTable localSymTable = Globals.program.getLocalSymbolTable();
		Symbol symbol = localSymTable.getSymbolGivenAddressLocalOrGlobal(addrStr);
		if (symbol != null) {
			// System.out.println("Symbol: " + symbol.getName());
			return symbol.getName();
		}
		System.err.println("addrToTextSymbol(): Error translating address to label");
		return null;
	}


	/**
	 * @return the current stack pointer ($sp) value.
	 */
	private int getSpValue() {
		return RegisterFile.getValue(SP_REG_NUMBER);
	}


	private void disableBackStepper() {
		/*
		 * The ignoreObserving flag is required for disabling
		 * BackStepper when the Connect button is pressed.
		 * (The tool is not yet registered as observing)
		 */
		if (Globals.program == null)
			return;
		BackStepper bs = Globals.program.getBackStepper();
		if (bs == null)
			return;
		if (bs.enabled()) {
			if (debugBackStepper)
				System.out.println("Disabled BackStepper");
			bs.setEnabled(false);
			disabledBackStep = true;
		}
	}


	private void restoreBackStepper() {
		if (disabledBackStep) {
			disabledBackStep = false;
			if (Globals.program == null)
				return;
			BackStepper bs = Globals.program.getBackStepper();
			if (bs == null)
				return;
			if (!bs.enabled()) {
				if (debugBackStepper)
					System.out.println("Enabled BackStepper");
				bs.setEnabled(true);
			}
		}
	}


	private void runButtonsSetEnabled(boolean enable) {
		if (enable == true)
			FileStatus.set(FileStatus.RUNNABLE);
		else
			FileStatus.set(FileStatus.TERMINATED);
	}


	/**
	 * Aligns the given address to the corresponding full-word boundary, if not already aligned.
	 *
	 * @param memAddress the memory address to be aligned (any {@code int} value is potentially valid).
	 * @return the calculated word-aligned address (divisible by {@code WORD_LENGTH_BYTES}).
	 */
	private int alignToCurrentWordBoundary(int memAddress) {
		if (Memory.wordAligned(memAddress))
			return memAddress;
		return (Memory.alignToWordBoundary(memAddress) - WORD_LENGTH_BYTES);
	}


	/**
	 * Formats a memory address to hexadecimal or decimal representation according to {@code hexAddressesCheckBox}.
	 *
	 * @param memAddress the memory address to be formatted.
	 * @return a string containing the hexadecimal or decimal representation of {@code memAddress}.
	 */
	private String formatAddress(int memAddress) {
		if (hexAddressesCheckBox.isSelected())
			return Binary.intToHexString(memAddress);
		else
			return Integer.toString(memAddress);
	}


	/**
	 * Formats memory contents of N-byte length to hexadecimal or decimal representation
	 * according to hexAddressesCheckBox. In case of hexadecimal representation, no
	 * "0x" prefix is added.
	 *
	 * @param numBytes memory content length in bytes.
	 * @param data memory content to be formatted.
	 * @return a string containing the hexadecimal or decimal representation of data.
	 */
	private String formatNByteLengthMemContents(int numBytes, int data) {
		if (hexValuesCheckBox.isSelected())
			return nBytesToHexStringNoPrefix(numBytes, data);
		else
			return Integer.toString(data);
	}


	/**
	 * Formats data of N-byte length to a hexadecimal representation string without a "0x" prefix.
	 *
	 * @param numBytes data length in bytes.
	 * @param data the data to be formatted.
	 * @return a string containing the resulted 2*N hexadecimal digits.
	 * Leading zeros are added if required.
	 */
	private String nBytesToHexStringNoPrefix(int numBytes, int data) {
		String leadingZero = new String("0");
		String ret = Integer.toHexString(data);
		while (ret.length() < (numBytes<<1)) // Add leading zeros if required
			ret = leadingZero.concat(ret);
		return ret;
	}


	/**
	 * Print the binary representation of a number without a "0b" prefix.
	 *
	 * @param num the number to be printed.
	 */
	@SuppressWarnings("unused")
	private void printBin(int num) {
		int count = 0;
		for (int i = count = 0; i < WORD_LENGTH_BITS; i++, num <<= 1)
			System.out.print((((num & (1 << WORD_LENGTH_BITS)) != 0) ? "1" : "0") +
					((++count % 4 == 0) ? " " : ""));
		System.out.print("\n");
	}


	@Override
	protected void reset() {
		/*
		 * Do not reset/clear here ras or activeFunctionCallStats.
		 */
		if (debug) {
			System.out.println("ToolReset");
		}
		getStackData();
		updateSpDataRowColIndex();
		table.repaint();
	}


	/**
	 * Update data table indexes ({@code spDataRowIndex},{@code spDataColumnIndex})
	 * as of where $sp points to.
	 */
	private void updateSpDataRowColIndex() {
		int spValue = getSpValue();
		try {
			spDataRowIndex = getTableRowIndex(spValue);
			spDataColumnIndex = getTableColumnIndex(spValue);
		} catch (SVException sve) {
			System.err.println("updateSpDataRowColIndex(): " + sve.getMessage());
		}
	}


	private void resetStoredRegAndFrameNameColumns(int startRow, int endRow) {
		for (int row = startRow; row <= endRow; row++) {
			tableModel.setValueAt("", row, storedRegisterColumn);
			tableModel.setValueAt("", row, frameNameColumn);
		}
	}


	private int calcTableColIndex(int offsetFromEnd) {
		return numberOfColumns - offsetFromEnd - 1;
	}


	/**
	 * A {@link SimulatorNotice} is handled locally in {@link StackVisualizer}, while all
	 * other notices are handled by supertype {@link AbstractMarsToolAndApplication}.
	 */
	@Override
	public void update(Observable observable, Object accessNotice) {
		if (observable == mars.simulator.Simulator.getInstance()) {
			processSimulatorUpdate((SimulatorNotice) accessNotice);
		} else {
			super.update(observable, accessNotice);
		}
	}


	/**
	 * Process a {@link SimulatorNotice} and handle {@code SIMULATOR_START} or
	 * {@code SIMULATOR_STOP} accordingly.
	 */
	private void processSimulatorUpdate(SimulatorNotice notice) {
		int action = notice.getAction();
		if (debug)
			System.out.println("\nSimulatorNotice: " + ((action == SimulatorNotice.SIMULATOR_START) ? "Start" : "End"));
		if (action == SimulatorNotice.SIMULATOR_START)
			onSimulationStart();
		else if (action == SimulatorNotice.SIMULATOR_STOP)
			onSimulationEnd();
	}


	/**
	 * Callback method after a simulation starts.
	 * A simulation starts each time a Run button is pressed (stepped or not).
	 */
	private void onSimulationStart() {
		if (!isObserving())
			return;
//		System.err.println("SIMULATION - START: " + inSteppedExecution());
		if (VenusUI.getReset()) { // GUI Reset button clicks are also handled here.
			if (debug)
				System.out.println("GUI registers/memory reset detected");
			/*
			 * On registers/memory reset, clear data related to subroutine calls,
			 * and reset/update table data.
			 */
			ras.clear();
			activeFunctionCallStats.reset();
			resetStoredRegAndFrameNameColumns(0, numberOfRows-1);
			getStackData();
			updateSpDataRowColIndex();
			table.repaint();
		}
		disableBackStepper();
		connectButton.setEnabled(false);
	}


	/**
	 * Callback method after a simulation ends.
	 * A simulation starts/ends each time a Run button is pressed (stepped or not).
	 */
	private void onSimulationEnd() {
		if (!isObserving())
			return;
//		System.err.println("SIMULATION - END: " + inSteppedExecution());
		connectButton.setEnabled(true);
	}


	/**
	 * @return true if we are in stepped execution.
	 */
//	private boolean inSteppedExecution() {
//		if (Globals.program == null)
//			return false;
//		return Globals.program.inSteppedExecution();
//	}


	@Override
	protected JComponent getHelpComponent() {
		final String helpContent = "Stack Visualizer\n\n"
				+ "Release: " + versionID + "   (" + releaseDate + ")\n\n"
				+ "Developed by George Z. Zachos (gzachos@cse.uoi.gr) and\n"
				+ "Petros Manousis (pmanousi@cs.uoi.gr) under the supervision of\n"
				+ "Aristides (Aris) Efthymiou (efthym@cse.uoi.gr).\n\n"
				+ "About\n"
				+ "This tool allows the user to view in real time the $sp-relative memory modification\n"
				+ "operations taking place in the stack segment. The user can also observe how the stack\n"
				+ " grows. The address pointed by the stack pointer is displayed in an orange background\n"
				+ "while the whole word-length data in a yellow one. Lower addresses have a grey\n"
				+ "background (given that stack growth takes place form higher to lower addresses).\n"
				+ "The names of the registers whose contents are stored (sw, sh, sb etc.) in the\n"
				+ "stack, are shown in the \"Stored Reg\" column. In the \"Call Layout\" column, the subroutine\n"
				+ "frame (activation record) layout is displayed, with subroutine names placed on the highest\n"
				+ "address of the corresponding frames.\n\n"
				+ "GitHub repository: https://github.com/gzachos/mars-stack-visualizer\n\n"
				+ "Contact\n"
				+ "For questions or comments contact: George Z. Zachos (gzachos@cse.uoi.gr) or\n"
				+ "Aristides Efthymiou (efthym@cse.uoi.gr)";
		JButton help = new JButton("Help");
		help.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				showMessageWindow(helpContent);
			}
		});
		return help;
	}


	private void showMessageWindow(String message) {
		JOptionPane.showMessageDialog(theWindow, message);
	}


	/**
	 * Active subroutine call statistics.
	 */
	private static class ActiveSubroutineStats {
		private HashMap<String, Integer> activeCalls;

		public ActiveSubroutineStats() {
			activeCalls = new HashMap<>(0);
		}

		/**
		 * Adds one more subroutine call to statistics.
		 *
		 * @param subroutineName name of subroutine to be added.
		 * @return the number of active subroutine calls of {@code subroutineName}.
		 */
		public Integer addCall(String subroutineName) {
			Integer newValue;
			if (activeCalls.containsKey(subroutineName)) {
				newValue = activeCalls.get(subroutineName) + 1;
				activeCalls.replace(subroutineName, newValue);
			} else {
				activeCalls.put(subroutineName, 1);
				newValue = 1;
			}
			return newValue;
		}

		/**
		 * Removes one subroutine call from statistics.
		 *
		 * @param subroutineName name of subroutine to be removed.
		 */
		public void removeCall(String subroutineName) {
			Integer oldValue = activeCalls.get(subroutineName);
			if (oldValue == null)
				System.err.println("ActiveFunctionCallStats.removeCall: " + subroutineName + " doesn't exist");
			else
				activeCalls.replace(subroutineName, oldValue - 1);
		}

		/**
		 * Reset active call statistics.
		 */
		public void reset() {
			activeCalls.clear();
		}
	}
	

	/**
	 * Trivial {@link Exception} implementation for {@link StackVisualizer}.
	 */
	private static class SVException extends Exception {
		public SVException(String message) {
			super(message);
		}
	}

}
