package mars.tools;

import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.util.Observable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;

import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;
import mars.mips.hardware.RegisterAccessNotice;
import mars.mips.hardware.RegisterFile;

@SuppressWarnings("serial")
public class StackVisualizer extends AbstractMarsToolAndApplication {

	private static String name = "Stack Visualizer";
	private static String version = "Version 0.1 (George Z. Zachos, Petros Manousis)";
	private static String heading = "Visualizing stack modification operations";

	private static int     spRegNumber = RegisterFile.STACK_POINTER_REGISTER;
	private static int     spAddr      = Memory.stackPointer;
	private static Memory  memInstance = Memory.getInstance();
	private static boolean endianness  = memInstance.getByteOrder();
	
	private static int        memRange = 8;
	private static Object[][] data     = new Object[memRange][5];;
	private static Object[]   colNames = {"Address", "-0", "-1", "-2", "-3"};
	private static JTable     table;
	private static JPanel     panel;

	
	protected StackVisualizer(String title, String heading) {
		super(title, heading);
	}

	public StackVisualizer() {
		super(StackVisualizer.name + ", " + StackVisualizer.version, StackVisualizer.heading);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	protected JComponent buildMainDisplayArea() {
		panel = new JPanel(new GridBagLayout());
		panel.setPreferredSize(new Dimension(600, 650));
		getStackData();
		return panel;
	}

	/*
	 * getStackData() fires a MemoryAccessNotice every time
	 * it reads from memory. For this reason it should not be
	 * called in a code block handling a MemoryAccessNotice
	 * of AccessNotice.READ type as it will lead in infinite
	 * recursive calls of itself.
	 */
	protected void getStackData() {
		int col;
		System.out.println("getStackData start");
		/*
		 * Initial value of spAddr is 0x7FFFEFFC = 2147479548.
		 * We ignore the word starting @0x7FFFEFFC, hence the
		 * first 4 bytes (1 word) to be displayed are:
		 * 0x7FFFEFFB, 0x7FFFEFFA, 0x7FFFEFF9, 0x7FFFEFF8 or in decimal value:
		 * 2147479547, 2147479546, 2147479545, 2147479544.
		 */
		for (int i = 0, addr = spAddr-1; i < memRange; i++)
		{
			data[i][0] = "0x" + hex(addr);
			try {
				for (int j = 1; j <= 4; j++) {
					col = (endianness == Memory.LITTLE_ENDIAN) ? j : 5-j;
//					System.out.println("(" + i + "," + j + ") - " 
//							+ addr +": " + memInstance.getByte(addr));
//					System.out.println("(" + i + "," + j + ") - " 
//							+ addr +": " + hex(memInstance.getByte(addr)));
					data[i][col] = hex(memInstance.getByte(addr--));
				}
				System.out.println(data[i][0] + ": " + data[i][1] + "," +
						data[i][2] + "," + data[i][3] + "," + data[i][4]);
/*				System.out.println(data[i][0] + ": " + hex((int)data[i][1]) + ","
						+ hex((int)data[i][2]) + "," + hex((int)data[i][3]) + ","
						+ hex((int)data[i][4]));*/
			} catch (AddressErrorException aee) {
				aee.printStackTrace();
			}
		}
		if (table != null)
			panel.remove(table);
		table = new JTable(data, colNames);
		table.setEnabled(false);
		panel.add(table);
		table.revalidate();
		System.out.println("getStackData end\n");
	}
	
	@Override
	protected void addAsObserver() {
		addAsObserver(Memory.stackLimitAddress, Memory.stackBaseAddress);
		addAsObserver(RegisterFile.getRegisters()[spRegNumber]);
	}
	
	@Override
	protected void processMIPSUpdate(Observable resource, AccessNotice notice) {

//		System.out.println(notice.accessIsFromMIPS() +" " + notice.accessIsFromGUI());

		if (!notice.accessIsFromMIPS())
			return;
		
		if (notice instanceof MemoryAccessNotice) {
			MemoryAccessNotice m = (MemoryAccessNotice) notice;
			if (m.getAccessType() == AccessNotice.READ)
				return;
			System.out.println("MemoryAccessNotice (" + 
					((m.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
					+ hex(m.getAddress()) + " value: " + m.getValue());
			getStackData();
		}
		else if (notice instanceof RegisterAccessNotice) {
			RegisterAccessNotice r = (RegisterAccessNotice) notice;
			if (r.getAccessType() == AccessNotice.READ)
				return;
			System.out.println("RegisterAccessNotice (W): " + r.getRegisterName()
					+ " value: " + hex(getSpValue()));
		}
	}

	private String hex(int decimalValue) {
		return Integer.toHexString(decimalValue);
	}

	private int getSpValue() {
		return RegisterFile.getValue(spRegNumber);
	}
	
}
